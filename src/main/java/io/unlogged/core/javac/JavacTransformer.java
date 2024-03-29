/*
 * Copyright (C) 2009-2019 The Project Lombok Authors.
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
package io.unlogged.core.javac;

import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import io.unlogged.core.CleanupRegistry;

import javax.annotation.processing.Messager;
import java.util.List;
import java.util.SortedSet;

public class JavacTransformer {
    private final HandlerLibrary handlers;
    private final Messager messager;

    public JavacTransformer(Messager messager, Trees trees, Context context) {
        this.messager = messager;
        this.handlers = HandlerLibrary.load(messager, trees, context);
    }

    public SortedSet<Long> getPriorities() {
        return handlers.getPriorities();
    }

    public SortedSet<Long> getPrioritiesRequiringResolutionReset() {
        return handlers.getPrioritiesRequiringResolutionReset();
    }


    public void transform(Context context, List<JCCompilationUnit> compilationUnits, CleanupRegistry cleanup) {
        for (JCCompilationUnit unit : compilationUnits) {
            JavacAST ast = new JavacAST(messager, context, unit, cleanup);
            ast.traverse(new AnnotationVisitor(0));
            handlers.callASTVisitors(ast);
            if (ast.isChanged()) UnloggedOptions.markChanged(context, (JCCompilationUnit) ast.top().get());
        }
    }

    public void finish(Context context, CleanupRegistry cleanup) {
        handlers.finish(messager, context, cleanup);
    }

    private class AnnotationVisitor extends JavacASTAdapter {
        private final long priority;

        AnnotationVisitor(long priority) {
            this.priority = priority;
        }

        @Override
        public void visitAnnotationOnType(JCClassDecl type, JavacNode annotationNode, JCAnnotation annotation) {
            JCCompilationUnit top = (JCCompilationUnit) annotationNode.top().get();
            handlers.handleAnnotation(top, annotationNode, annotation, priority);
        }

        @Override
        public void visitAnnotationOnField(JCVariableDecl field, JavacNode annotationNode, JCAnnotation annotation) {
            JCCompilationUnit top = (JCCompilationUnit) annotationNode.top().get();
            handlers.handleAnnotation(top, annotationNode, annotation, priority);
        }

        @Override
        public void visitAnnotationOnMethod(JCMethodDecl method, JavacNode annotationNode, JCAnnotation annotation) {
            JCCompilationUnit top = (JCCompilationUnit) annotationNode.top().get();
            handlers.handleAnnotation(top, annotationNode, annotation, priority);
        }

        @Override
        public void visitAnnotationOnMethodArgument(JCVariableDecl argument, JCMethodDecl method, JavacNode annotationNode, JCAnnotation annotation) {
            JCCompilationUnit top = (JCCompilationUnit) annotationNode.top().get();
            handlers.handleAnnotation(top, annotationNode, annotation, priority);
        }

        @Override
        public void visitAnnotationOnLocal(JCVariableDecl local, JavacNode annotationNode, JCAnnotation annotation) {
            JCCompilationUnit top = (JCCompilationUnit) annotationNode.top().get();
            handlers.handleAnnotation(top, annotationNode, annotation, priority);
        }

        @Override
        public void visitAnnotationOnTypeUse(JCTree typeUse, JavacNode annotationNode, JCAnnotation annotation) {
            JCCompilationUnit top = (JCCompilationUnit) annotationNode.top().get();
            handlers.handleAnnotation(top, annotationNode, annotation, priority);
        }
    }
}
