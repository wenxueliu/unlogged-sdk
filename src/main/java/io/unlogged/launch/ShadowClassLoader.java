/*
 * Copyright (C) 2014-2021 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.unlogged.launch;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The shadow classloader serves to completely hide almost all classes in a given jar file by using a different file ending.
 * 
 * The shadow classloader also serves to link in a project as it is being developed (a 'bin' dir from an IDE for example).
 * <p>
 * Classes loaded by the shadowloader use ".SCL.<em>sclSuffix</em>" in addition to ".class". In other words, most of the class files in a given jar end in this suffix, which
 * serves to hide them from any tool that isn't aware of the suffix (such as IDEs generating auto-complete dialogs, and javac's classpath in general). Only shadowloader can actually
 * load these classes.
 * <p>
 * The shadowloader will pick up an alternate (priority) classpath, using normal class files, from the system property "<code>shadow.override.<em>sclSuffix</em></code>".
 * This shadow classpath looks just like a normal java classpath; the path separator is applied (semi-colon on windows, colon elsewhere), and entries can consist of directories,
 * jar files, or directories ending in "/*" to pick up all jars inside it.
 * <p>
 * Load order is as follows if at least one override is present:
 * <li>First, if the resource is found in one of the paths stated in the shadow classpath, find that.
 * <li>Next, ask the <code>parent</code> loader, which is passed during construction of the ShadowClassLoader.
 * <li>Notably, this jar's contents are always skipped! (The idea of the shadow classpath is that this jar only functions as a launcher, not as a source of your actual application).
 * </ul>
 * 
 * If no overrides are present, the load order is as follows:
 * <li>First, if the resource is found in our own jar (trying ".SCL.<em>sclSuffix</em>" first for any resource request ending in ".class"), return that.
 * <li>Next, check any jar files other than our own, loading them via this classloader, if they have a file <code>META-INF/ShadowClassLoader</code> that contains a line of text with <em>sclSuffix</em>.
 * <li>Next, ask the <code>parent</code> loader.
 * </ul>
 * 
 * Use ShadowClassLoader to accomplish the following things:<ul>
 * <li>Avoid contaminating the namespace of any project using an SCL-based jar. Autocompleters in IDEs will NOT suggest anything other than actual public API.
 * <li>Like jarjar, allows folding in dependencies such as ASM without foisting these dependencies on projects that use this jar. shadowloader obviates the need for jarjar.
 * <li>Allows an agent (which MUST be in jar form) to still load everything except this loader infrastructure from class files generated by the IDE, which should
 * considerably help debugging, as you can now rely on the IDE's built-in auto-recompile features instead of having to run a full build everytime, and it should help
 * with hot code replace and the like (this is what the {@code shadow.override} feature is for).
 * </ul>
 * 
 * Implementation note: {@code lombok.eclipse.agent.EclipseLoaderPatcher} <em>relies</em> on this class having no dependencies on any other class except the JVM boot class, notably
 * including any other classes in this package, <strong>including</strong> inner classes. So, don't write closures, anonymous inner class literals,
 * enums, or anything else that could cause the compilation of this file to produce more than 1 class file. In general, actually passing load control to this loader is a bit tricky
 * so ensure that this class has zero dependencies on anything except java core classes.
 */
class ShadowClassLoader extends ClassLoader {
	private static final String SELF_NAME = "io/unlogged/launch/ShadowClassLoader.class";
	private static final ConcurrentMap<String, Class<?>> highlanderMap = new ConcurrentHashMap<String, Class<?>>();
	
	private final String SELF_BASE;
	private final File SELF_BASE_FILE;
	private final int SELF_BASE_LENGTH;
	
	private final List<File> override = new ArrayList<File>();
	private final String sclSuffix;
	private final List<String> parentExclusion = new ArrayList<String>();
	private final List<String> highlanders = new ArrayList<String>();
	
	private final Set<ClassLoader> prependedParentLoaders = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
	
	public void prependParent(ClassLoader loader) {
		if (loader == null) return;
		if (loader == getParent()) return;
		prependedParentLoaders.add(loader);
	}
	
	/**
	 * @param source The 'parent' classloader.
	 * @param sclSuffix The suffix of the shadowed class files in our own jar. For example, if this is {@code lombok}, then the class files in your jar should be {@code foo/Bar.SCL.lombok} and not {@code foo/Bar.class}.
	 * @param selfBase The (preferably absolute) path to our own jar. This jar will be searched for class/SCL.sclSuffix files.
	 * @param parentExclusion For example {@code "lombok."}; upon invocation of loadClass of this loader, the parent loader ({@code source}) will NOT be invoked if the class to be loaded begins with anything in the parent exclusion list. No exclusion is applied for getResource(s).
	 * @param highlanders SCL will put in extra effort to ensure that these classes (in simple class spec, so {@code foo.bar.baz.ClassName}) are only loaded once as a class, even if many different classloaders try to load classes, such as equinox/OSGi.
	 */
	ShadowClassLoader(ClassLoader source, String sclSuffix, String selfBase, List<String> parentExclusion, List<String> highlanders) {
		super(source);
		this.sclSuffix = sclSuffix;
		if (parentExclusion != null) for (String pe : parentExclusion) {
			pe = pe.replace(".", "/");
			if (!pe.endsWith("/")) pe = pe + "/";
			this.parentExclusion.add(pe);
		}
		if (highlanders != null) for (String hl : highlanders) this.highlanders.add(hl);
		
		if (selfBase != null) {
			SELF_BASE = selfBase;
			SELF_BASE_LENGTH = selfBase.length();
		} else {
			URL sclClassUrl = ShadowClassLoader.class.getResource("ShadowClassLoader.class");
			String sclClassStr = sclClassUrl == null ? null : sclClassUrl.toString();
			if (sclClassStr == null || !sclClassStr.endsWith(SELF_NAME)) {
				ClassLoader cl = ShadowClassLoader.class.getClassLoader();
				throw new RuntimeException("ShadowLoader can't find itself. SCL loader type: " + (cl == null ? "*NULL*" : cl.getClass().toString()));
			}
			SELF_BASE_LENGTH = sclClassStr.length() - SELF_NAME.length();
			String decoded = urlDecode(sclClassStr.substring(0, SELF_BASE_LENGTH));
			SELF_BASE = decoded;
		}
		
		if (SELF_BASE.startsWith("jar:file:") && SELF_BASE.endsWith("!/")) SELF_BASE_FILE = new File(SELF_BASE.substring(9, SELF_BASE.length() - 2));
		else if (SELF_BASE.startsWith("file:")) SELF_BASE_FILE = new File(SELF_BASE.substring(5));
		else SELF_BASE_FILE = new File(SELF_BASE);
		String scl = System.getProperty("shadow.override." + sclSuffix);
		if (scl != null && !scl.isEmpty()) {
			for (String part : scl.split("\\s*" + (File.pathSeparatorChar == ';' ? ";" : ":") + "\\s*")) {
				if (part.endsWith("/*") || part.endsWith(File.separator + "*")) {
					addOverrideJarDir(part.substring(0, part.length() - 2));
				} else {
					addOverrideClasspathEntry(part);
				}
			}
		}
	}
	
	private final Map<String, Object> mapJarPathToTracker = new HashMap<String, Object>();
	private static final Map<Object, String> mapTrackerToJarPath = new WeakHashMap<Object, String>();
	private static final Map<Object, Set<String>> mapTrackerToJarContents = new WeakHashMap<Object, Set<String>>();
	
	/**
	 * This cache ensures that any given jar file is only opened once in order to determine the full contents of it.
	 * We use 'trackers' to make sure that the bulk of the memory taken up by this cache (the list of strings representing the content of a jar file)
	 * gets garbage collected if all ShadowClassLoaders that ever tried to request a listing of this jar file, are garbage collected.
	 */
	private Set<String> getOrMakeJarListing(final String absolutePathToJar) {
		synchronized (mapTrackerToJarPath) {
			/*
			 * 1) Check our private instance JarPath-to-Tracker Mappings:
			 */
			Object ourTracker = mapJarPathToTracker.get(absolutePathToJar);
			if (ourTracker != null) {
				/*
				 * Yes, we are already tracking this Jar. Just return its contents...
				 */
				return mapTrackerToJarContents.get(ourTracker);
			}
			
			/*
			 * 2) Not tracked by us as yet. Check statically whether others have tracked this JarPath:
			 */
			for (Entry<Object, String> entry : mapTrackerToJarPath.entrySet()) {
				if (entry.getValue().equals(absolutePathToJar)) {
					/*
					 * Yes, 3rd party is tracking this jar. We must track too, then return its contents.
					 */
					Object otherTracker = entry.getKey();
					mapJarPathToTracker.put(absolutePathToJar, otherTracker);
					return mapTrackerToJarContents.get(otherTracker);
				}
			}
			
			/*
			 * 3) Not tracked by anyone so far. Build, publish, track & return Jar contents...
			 */
			Object newTracker = new Object();
			Set<String> jarMembers = getJarMemberSet(absolutePathToJar);
			
			mapTrackerToJarContents.put(newTracker, jarMembers);
			mapTrackerToJarPath.put(newTracker, absolutePathToJar);
			mapJarPathToTracker.put(absolutePathToJar, newTracker);
			
			return jarMembers;
		}
	}
	
	/**
	 * Return a {@link Set} of members in the Jar identified by {@code absolutePathToJar}.
	 * 
	 * @param absolutePathToJar Cache key
	 * @return a Set with the Jar member-names
	 */
	private Set<String> getJarMemberSet(String absolutePathToJar) {
		/*
		 * Note:
		 * Our implementation returns a HashSet. initialCapacity and loadFactor are carefully tweaked for speed and RAM optimization purposes.
		 * 
		 * Benchmark:
		 * The HashSet implementation is about 10% slower to build (only happens once) than the ArrayList.
		 * The HashSet with shiftBits = 1 was about 33 times(!) faster than the ArrayList for retrievals.
		 */
		try {
			int shiftBits = 1;  //  (fast, but big)  0 <= shiftBits <= 5, say  (slower & compact)
			JarFile jar = new JarFile(absolutePathToJar);
			
			/*
			 * Find the first power of 2 >= JarSize (as calculated in HashSet constructor)
			 */
			int jarSizePower2 = Integer.highestOneBit(jar.size());
			if (jarSizePower2 != jar.size()) jarSizePower2 <<= 1;
			if (jarSizePower2 == 0) jarSizePower2 = 1;
			
			Set<String> jarMembers = new HashSet<String>(jarSizePower2 >> shiftBits,  1 << shiftBits);
			try {
				Enumeration<JarEntry> entries = jar.entries();
				while (entries.hasMoreElements()) {
					JarEntry jarEntry = entries.nextElement();
					if (jarEntry.isDirectory()) continue;
					jarMembers.add(jarEntry.getName());
				}
			} catch (Exception ignore) {
				// ignored; if the jar can't be read, treating it as if the jar contains no classes is just what we want.
			} finally {
				jar.close();
			}
			return jarMembers;
		}
		catch (Exception newJarFileException) {
			return Collections.emptySet();
		}
	}
	
	/**
	 * Looks up {@code altName} in {@code location}, and if that isn't found, looks up {@code name}; {@code altName} can be null in which case it is skipped.
	 */
	private URL getResourceFromLocation(String name, String altName, File location) {
		if (location.isDirectory()) {
			try {
				if (altName != null) {
					File f = new File(location, altName);
					if (f.isFile() && f.canRead()) return f.toURI().toURL();
				}
				
				File f = new File(location, name);
				if (f.isFile() && f.canRead()) return f.toURI().toURL();
				return null;
			} catch (MalformedURLException e) {
				return null;
			}
		}
		
		if (!location.isFile() || !location.canRead()) return null;
		
		File absoluteFile; {
			try {
				absoluteFile = location.getCanonicalFile();
			} catch (Exception e) {
				absoluteFile = location.getAbsoluteFile();
			}
		}
		Set<String> jarContents = getOrMakeJarListing(absoluteFile.getAbsolutePath());
		
		String absoluteUri = absoluteFile.toURI().toString();
		
		try {
			if (jarContents.contains(altName)) {
				return new URI("jar:" + absoluteUri + "!/" + altName).toURL();
			}
		} catch (Exception ignore) {
			// intentional fallthrough
		}
		
		try {
			if (jarContents.contains(name)) {
				return new URI("jar:" + absoluteUri + "!/" + name).toURL();
			}
		} catch(Exception ignore) {
			// intentional fallthrough
		}
		
		return null;
	}
	
	private boolean partOfShadow(String item, String name) {
		return !name.startsWith("java/") 
				&& !name.startsWith("sun/") 
				&& (inOwnBase(item, name) || isPartOfShadowSuffix(item, name, sclSuffix));
	}
	
	/**
	 * Checks if the stated item is located inside the same classpath root as the jar that hosts ShadowClassLoader.class. {@code item} and {@code name} refer to the same thing.
	 */
	private boolean inOwnBase(String item, String name) {
		if (item == null) return false;
		return (item.length() == SELF_BASE_LENGTH + name.length()) && SELF_BASE.regionMatches(0, item, 0, SELF_BASE_LENGTH);
	}
	
	private static boolean sclFileContainsSuffix(InputStream in, String suffix) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
		for (String line = br.readLine(); line != null; line = br.readLine()) {
			line = line.trim();
			if (line.isEmpty() || line.charAt(0) == '#') continue;
			if (line.equals(suffix)) return true;
		}
		return false;
	}
	
	private static String urlDecode(String in) {
		final String plusFixed = in.replaceAll("\\+", "%2B");
		try {
			return URLDecoder.decode(plusFixed, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new InternalError("UTF-8 not supported");
		}
	}
	
	private Map<String, Boolean> fileRootCache = new HashMap<String, Boolean>();
	private boolean isPartOfShadowSuffixFileBased(String fileRoot, String suffix) {
		String key = fileRoot + "::" + suffix;
		Boolean existing = fileRootCache.get(key);
		if (existing != null) return existing.booleanValue();

		File f = new File(fileRoot + "/META-INF/ShadowClassLoader");
		try {
			FileInputStream fis = new FileInputStream(f);
			try {
				boolean v = sclFileContainsSuffix(fis, suffix);
				fileRootCache.put(key, v);
				return v;
			} finally {
				fis.close();
			}
		} catch (FileNotFoundException fnfEx) {
			fileRootCache.put(key, false);
			return false;
		} catch (IOException e) {
			fileRootCache.put(key, false);
			return false; // *unexpected*
		}
	}

	private Map<String, Boolean> jarLocCache = new HashMap<String, Boolean>();
	private boolean isPartOfShadowSuffixJarBased(String jarLoc, String suffix) {
		String key = jarLoc + "::" + suffix;
		Boolean existing = jarLocCache.get(key);
		if (existing != null) return existing.booleanValue();

		if (jarLoc.startsWith("file:/")) jarLoc = urlDecode(jarLoc.substring(5));
		try {
			FileInputStream jar = new FileInputStream(jarLoc);
			try {
				ZipInputStream zip = new ZipInputStream(jar);
				try {
					while (true) {
						ZipEntry entry = zip.getNextEntry();
						if (entry == null) {
							jarLocCache.put(key, false);
							return false;
						}
						if (!"META-INF/ShadowClassLoader".equals(entry.getName())) continue;
						boolean v = sclFileContainsSuffix(zip, suffix);
						jarLocCache.put(key, v);
						return v;
					}
				} finally {
					zip.close();
				}
			} finally {
				jar.close();
			}
		} catch (FileNotFoundException fnfEx) {
			jarLocCache.put(key, false);
			return false;
		} catch (IOException ex) {
			jarLocCache.put(key, false);
			return false; // *unexpected*
		}
	}

	private boolean isPartOfShadowSuffix(String url, String name, String suffix) {
		// Instead of throwing an exception or logging, weird, unexpected cases just return false.
		// This is better than throwing an exception, because exceptions would make your build tools unusable.
		// Such cases are marked with the comment: // *unexpected*
		if (url == null) return false;
		if (url.startsWith("file:/")) {
			url = urlDecode(url.substring(5));
			if (url.length() <= name.length() || !url.endsWith(name) || url.charAt(url.length() - name.length() - 1) != '/') {
				return false; // *unexpected*
			}
			
			String fileRoot = url.substring(0, url.length() - name.length() - 1);
			return isPartOfShadowSuffixFileBased(fileRoot, suffix);
		} else if (url.startsWith("jar:")) {
			int sep = url.indexOf('!');
			if (sep == -1) {
				return false; // *unexpected*
			}
			String jarLoc = url.substring(4, sep);
			return isPartOfShadowSuffixJarBased(jarLoc, suffix);
		}
		
		return false;
	}
	
	@Override public Enumeration<URL> getResources(String name) throws IOException {
		String altName = null;
		if (name.endsWith(".class")) altName = name.substring(0, name.length() - 6) + ".SCL." + sclSuffix;
		
		// Vector? Yes, we need one:
		// * We can NOT make inner classes here (this class is loaded with special voodoo magic in eclipse, as a one off, it's not a full loader.
		// * We need to return an enumeration.
		// * We can't make one on the fly.
		// * ArrayList can't make these.
		Vector<URL> vector = new Vector<URL>();
		
		for (File ce : override) {
			URL url = getResourceFromLocation(name, altName, ce);
			if (url != null) vector.add(url);
		}
		
		if (override.isEmpty()) {
			URL fromSelf = getResourceFromLocation(name, altName, SELF_BASE_FILE);
			if (fromSelf != null) vector.add(fromSelf);
		}
		
		Enumeration<URL> sec = super.getResources(name);
		while (sec.hasMoreElements()) {
			URL item = sec.nextElement();
			if (isPartOfShadowSuffix(item.toString(), name, sclSuffix)) vector.add(item);
		}
		
		if (altName != null) {
			Enumeration<URL> tern = super.getResources(altName);
			while (tern.hasMoreElements()) {
				URL item = tern.nextElement();
				if (isPartOfShadowSuffix(item.toString(), altName, sclSuffix)) vector.add(item);
			}
		}
		
		return vector.elements();
	}
	
	@Override public URL getResource(String name) {
		return getResource_(name, false);
	}
	
	private URL getResource_(String name, boolean noSuper) {
		String altName = null;
		if (name.endsWith(".class")) altName = name.substring(0, name.length() - 6) + ".SCL." + sclSuffix;
		for (File ce : override) {
			URL url = getResourceFromLocation(name, altName, ce);
			if (url != null) return url;
		}
		
		if (!override.isEmpty()) {
			if (noSuper) return null;
			if (altName != null) {
				try {
					URL res = getResourceSkippingSelf(altName);
					if (res != null) return res;
				} catch (IOException ignore) {}
			}
			
			try {
				return getResourceSkippingSelf(name);
			} catch (IOException e) {
				return null;
			}
		}
		
		URL url = getResourceFromLocation(name, altName, SELF_BASE_FILE);
		if (url != null) return url;
		
		if (altName != null) {
			URL res = super.getResource(altName);
			if (res != null && (!noSuper || partOfShadow(res.toString(), altName))) return res;
		}
		
		URL res = super.getResource(name);
		if (res != null && (!noSuper || partOfShadow(res.toString(), name))) return res;
		return null;
	}
	
	private boolean exclusionListMatch(String name) {
		for (String pe : parentExclusion) {
			if (name.startsWith(pe)) return true;
		}
		return false;
	}
	
	private URL getResourceSkippingSelf(String name) throws IOException {
		URL candidate = super.getResource(name);
		if (candidate == null) return null;
		if (!partOfShadow(candidate.toString(), name)) return candidate;
		
		Enumeration<URL> en = super.getResources(name);
		while (en.hasMoreElements()) {
			candidate = en.nextElement();
			if (!partOfShadow(candidate.toString(), name)) return candidate;
		}
		
		return null;
	}
	
	@Override public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		{
			Class<?> alreadyLoaded = findLoadedClass(name);
			if (alreadyLoaded != null) return alreadyLoaded;
		}
		
		if (highlanders.contains(name)) {
			Class<?> c = highlanderMap.get(name);
			if (c != null) return c;
		}
		
		String fileNameOfClass = name.replace(".", "/") + ".class";
		URL res = getResource_(fileNameOfClass, true);
		if (res == null) {
			if (!exclusionListMatch(fileNameOfClass)) {
				try {
					// First search in the prepended classloaders, the class might be their already
					for (ClassLoader pre : prependedParentLoaders) {
						try {
							Class<?> loadClass = pre.loadClass(name);
							if (loadClass != null) return loadClass;
						} catch (Throwable e) {
							continue;
						}
					}
					
					return super.loadClass(name, resolve);
				} catch (ClassNotFoundException cnfe) {
					res = getResource_("secondaryLoading.SCL." + sclSuffix + "/" + name.replace(".", "/") + ".SCL." + sclSuffix, true);
					if (res == null) throw cnfe;
				}
			}
		}
		if (res == null) throw new ClassNotFoundException(name);
		
		return urlToDefineClass(name, res, resolve);
	}
	
	private Class<?> urlToDefineClass(String name, URL res, boolean resolve) throws ClassNotFoundException {
		byte[] b;
		int p = 0;
		try {
			InputStream in = res.openStream();
			
			try {
				b = new byte[65536];
				while (true) {
					int r = in.read(b, p, b.length - p);
					if (r == -1) break;
					p += r;
					if (p == b.length) {
						byte[] nb = new byte[b.length * 2];
						System.arraycopy(b, 0, nb, 0, p);
						b = nb;
					}
				}
			} finally {
				in.close();
			}
		} catch (IOException e) {
			throw new ClassNotFoundException("I/O exception reading class " + name, e);
		}
		
		Class<?> c;
		try {
			c = defineClass(name, b, 0, p);
		} catch (LinkageError e) {
			if (highlanders.contains(name)) {
				Class<?> alreadyDefined = highlanderMap.get(name);
				if (alreadyDefined != null) return alreadyDefined;
			}
			try {
				c = this.findLoadedClass(name);
			} catch (LinkageError e2) {
				throw e;
			}
			if (c == null) throw e;
		}
		
		if (highlanders.contains(name)) {
			Class<?> alreadyDefined = highlanderMap.putIfAbsent(name, c);
			if (alreadyDefined != null) c = alreadyDefined;
		}
		
		if (resolve) resolveClass(c);
		return c;
	}
	
	public void addOverrideJarDir(String dir) {
		File f = new File(dir);
		for (File j : f.listFiles()) {
			if (j.getName().toLowerCase().endsWith(".jar") && j.canRead() && j.isFile()) override.add(j);
		}
	}
	
	public void addOverrideClasspathEntry(String entry) {
		override.add(new File(entry));
	}
}
