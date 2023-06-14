package io.unlogged.weaver;

import com.insidious.common.weaver.*;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import io.unlogged.core.ImportList;
import io.unlogged.core.TypeLibrary;
import io.unlogged.core.TypeResolver;
import io.unlogged.core.handlers.JavacHandlerUtil;
import io.unlogged.core.javac.JavacASTAdapter;
import io.unlogged.core.javac.JavacNode;
import io.unlogged.core.javac.JavacTreeMaker;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class UnloggedVisitor extends JavacASTAdapter {

    private final Map<String, Boolean> methodRoots = new HashMap<>();
    private final Map<JCTree, ClassInfo> classRoots = new HashMap<>();
    private final Map<ClassInfo, JavacNode> classJavacNodeMap = new HashMap<>();
    private final Map<ClassInfo, java.util.List<MethodInfo>> classMethodInfoList = new HashMap<>();
    private final Map<ClassInfo, java.util.List<DataInfo>> classDataInfoList = new HashMap<>();
    private final DataInfoProvider dataInfoProvider;
    private final TypeLibrary typeLibrary = new TypeLibrary();

    public UnloggedVisitor(DataInfoProvider dataInfoProvider) {
        this.dataInfoProvider = dataInfoProvider;
    }

    @Override
    public void visitCompilationUnit(JavacNode top, JCTree.JCCompilationUnit unit) {
        for (JCTree.JCImport anImport : unit.getImports()) {
            typeLibrary.addType(anImport.getQualifiedIdentifier().toString());
        }

    }

    @Override
    public void visitMethod(JavacNode methodNode, JCTree.JCMethodDecl jcMethodDecl) {
        JavacNode classNode = methodNode.up();
        if (!JavacHandlerUtil.isClass(classNode)) {
            // no probing for interfaces and enums
            return;
        }
        JCTree classElement = classNode.get();
        String packageDeclaration = classNode.getPackageDeclaration();
        String className = classNode.getName();
        if (packageDeclaration != null) {
            className = packageDeclaration + "." + className;
        }
        List<JCTree.JCVariableDecl> methodParameters = jcMethodDecl.params;
        String methodDoneKey = className + jcMethodDecl.getName() + methodParameters.toString();

        ClassInfo classInfo;
        if (!classRoots.containsKey(classElement)) {

            ImportList importList = classNode.getImportList();
            TypeResolver resolver = new TypeResolver(importList);

            JCTree.JCClassDecl classDeclaration = (JCTree.JCClassDecl) classNode.get();
            Element element = classNode.getElement();
            String superClassFQN = "";
            if (classDeclaration.getExtendsClause() != null) {
                String superClassName = ((JCTree.JCIdent) classDeclaration.getExtendsClause()).getName().toString();
                superClassFQN = resolver.typeRefToFullyQualifiedName(classNode, typeLibrary, superClassName);
                if (superClassFQN == null && packageDeclaration != null) {
                    superClassFQN = packageDeclaration + "." + superClassName;
                }
            }
            String[] interfaces = {};
            List<JCTree.JCExpression> interfaceClasses = classDeclaration.getImplementsClause();
            if (interfaceClasses != null) {
                interfaces = new String[interfaceClasses.size()];
                for (int i = 0; i < interfaceClasses.size(); i++) {
                    JCTree.JCIdent interfaceClause = (JCTree.JCIdent) interfaceClasses.get(i);
                    String interfaceClassName = interfaceClause.getName().toString();
                    String interfaceClassFQN = resolver.typeRefToFullyQualifiedName(classNode, typeLibrary,
                            interfaceClassName);
                    if (interfaceClassFQN == null && packageDeclaration != null) {
                        interfaceClassFQN = packageDeclaration + "." + interfaceClassName;
                    }
                    interfaces[i] = interfaceClassFQN;
                }
            }

            classInfo = new ClassInfo(
                    dataInfoProvider.nextClassId(), "classContainer", classNode.up().getFileName(),
                    className, LogLevel.Normal, String.valueOf(element.hashCode()),
                    "classLoader", interfaces, superClassFQN, "signature");
            classRoots.put(classElement, classInfo);
            classDataInfoList.put(classInfo, new ArrayList<>());
            classMethodInfoList.put(classInfo, new ArrayList<>());
        } else {
            classInfo = classRoots.get(classElement);
        }
        classJavacNodeMap.put(classInfo, classNode);
        if (methodNode.isStatic()) {
            return;
        }

        if (methodRoots.containsKey(methodDoneKey)) {
            return;
        }
        String methodName = methodNode.getName();
        String methodDesc = createMethodDescriptor(methodNode);
        MethodInfo methodInfo = new MethodInfo(classInfo.getClassId(), dataInfoProvider.nextMethodId(),
                className, methodName, methodDesc, (int) jcMethodDecl.mods.flags,
                "sourceFileName", String.valueOf(jcMethodDecl.hashCode()));
        classMethodInfoList.get(classInfo).add(methodInfo);
        methodRoots.put(methodDoneKey, true);
        java.util.List<DataInfo> dataInfoList = classDataInfoList.get(classInfo);


        String methodSignature = methodParameters.toString();


        DataInfo dataInfo = new DataInfo(classInfo.getClassId(), methodInfo.getMethodId(),
                dataInfoProvider.nextProbeId(), methodNode.getStartPos(), 0,
                EventType.METHOD_ENTRY, Descriptor.Void, "");

        dataInfoList.add(dataInfo);

        ListBuffer<JCTree.JCStatement> parameterProbes = new ListBuffer<JCTree.JCStatement>();
        for (JCTree.JCVariableDecl methodParameter : methodParameters) {
            JCTree methodParameterType = methodParameter.getType();

            String methodParameterTypeFQN = "void";
            if (methodParameterType instanceof JCTree.JCIdent) {
                methodParameterTypeFQN = ((JCTree.JCIdent) methodParameterType).sym.getQualifiedName().toString();
            } else if (methodParameterType instanceof JCTree.JCPrimitiveTypeTree) {
                JCTree.JCPrimitiveTypeTree primitiveType = (JCTree.JCPrimitiveTypeTree) methodParameterType;
                methodParameterTypeFQN = primitiveType.toString();
            }

            DataInfo paramProbeDataInfo = new DataInfo(classInfo.getClassId(), methodInfo.getMethodId(),
                    dataInfoProvider.nextProbeId(), methodNode.getStartPos(), 0,
                    EventType.METHOD_PARAM, Descriptor.get(methodParameterTypeFQN), "");
            dataInfoList.add(paramProbeDataInfo);
            String parameterName = methodParameter.getName().toString();
            JCTree.JCExpressionStatement paramProbe = createLogStatement(methodNode, parameterName,
                    paramProbeDataInfo.getDataId());
            parameterProbes.add(paramProbe);
        }


        System.out.println("Visit method: " + className + "." + methodName + "( " + methodSignature + "  )");
        JavacTreeMaker treeMaker = methodNode.getTreeMaker();

        JCTree.JCExpressionStatement logStatement = createLogStatement(methodNode, "this", dataInfo.getDataId());

        ListBuffer<JCTree.JCStatement> newStatements = new ListBuffer<JCTree.JCStatement>();

        JCTree.JCBlock methodBodyBlock = jcMethodDecl.body;
        if (methodBodyBlock == null) {
            // no probes for empty method
            // maybe we fill it up later
            methodBodyBlock = treeMaker.Block(0, List.nil());
//            return;
        }

        List<JCTree.JCStatement> blockStatements = methodBodyBlock.getStatements();

        boolean foundSuperCall = false;
        if (!methodName.equals("<init>")) {
            foundSuperCall = true;
            newStatements.add(logStatement);
            newStatements.addAll(parameterProbes);

            for (JCTree.JCStatement statement : blockStatements) {
                System.out.println("===>\t\tStatement type: " + statement.getClass());
                ListBuffer<JCTree.JCStatement> normalizedStatements = normalizeStatements(statement, classNode);
                newStatements.addAll(normalizedStatements);
            }


        } else {
            for (JCTree.JCStatement statement : blockStatements) {

                System.out.println("===>\t\tStatement type: " + statement.getClass());
                newStatements.add(statement);


                if (!foundSuperCall) {
                    if (statement instanceof JCTree.JCExpressionStatement) {
                        JCTree.JCExpressionStatement expressionStatement = (JCTree.JCExpressionStatement) statement;
                        JCTree.JCExpression expression = expressionStatement.getExpression();
                        if (expression instanceof JCTree.JCMethodInvocation) {
                            JCTree.JCMethodInvocation methodInvocation = (JCTree.JCMethodInvocation) expression;
                            JCTree.JCExpression methodSelect = methodInvocation.getMethodSelect();
                            if (methodSelect instanceof JCTree.JCIdent) {
                                JCTree.JCIdent methodNameIdentifier = (JCTree.JCIdent) methodSelect;
                                if ("super".equals(methodNameIdentifier.getName().toString())) {
                                    foundSuperCall = true;
                                    newStatements.add(logStatement);
                                    newStatements.addAll(parameterProbes);
                                }
                            }
                        }
                    }
                }


            }
            if (!foundSuperCall) {
                newStatements.add(logStatement);
            }
        }


        jcMethodDecl.body = treeMaker.Block(0, newStatements.toList());
        System.out.println("After: " + jcMethodDecl.body);

    }

    private ListBuffer<JCTree.JCStatement> normalizeStatements(JCTree.JCStatement statement, JavacNode javacNode) {
        ListBuffer<JCTree.JCStatement> normalizedStatements = new ListBuffer<>();

        if (statement instanceof JCTree.JCAssert) {
            JCTree.JCAssert castedStatement = (JCTree.JCAssert) statement;
            normalizedStatements.add(statement);

        } else if (statement instanceof JCTree.JCBlock) {
            JCTree.JCBlock castedStatement = (JCTree.JCBlock) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCBreak) {
            JCTree.JCBreak castedStatement = (JCTree.JCBreak) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCCase) {
            JCTree.JCCase castedStatement = (JCTree.JCCase) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCClassDecl) {
            JCTree.JCClassDecl castedStatement = (JCTree.JCClassDecl) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCContinue) {
            JCTree.JCContinue castedStatement = (JCTree.JCContinue) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCDoWhileLoop) {
            JCTree.JCDoWhileLoop castedStatement = (JCTree.JCDoWhileLoop) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCEnhancedForLoop) {
            JCTree.JCEnhancedForLoop castedStatement = (JCTree.JCEnhancedForLoop) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCExpressionStatement) {

            JCTree.JCExpressionStatement castedStatement = (JCTree.JCExpressionStatement) statement;
            JCTree.JCExpression expression = castedStatement.getExpression();
//            normalizedStatements = normalizeExpression(expression);
            normalizedStatements.add(statement);

        } else if (statement instanceof JCTree.JCForLoop) {
            JCTree.JCForLoop castedStatement = (JCTree.JCForLoop) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCIf) {
            JCTree.JCIf castedStatement = (JCTree.JCIf) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCLabeledStatement) {
            JCTree.JCLabeledStatement castedStatement = (JCTree.JCLabeledStatement) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCReturn) {
            JCTree.JCReturn castedStatement = (JCTree.JCReturn) statement;
            JCTree.JCExpression returnExpression = castedStatement.getExpression();
            NormalizedStatement normalizedExpression = normalizeExpression(returnExpression, javacNode);
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCSkip) {
            JCTree.JCSkip castedStatement = (JCTree.JCSkip) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCSwitch) {
            JCTree.JCSwitch castedStatement = (JCTree.JCSwitch) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCSynchronized) {
            JCTree.JCSynchronized castedStatement = (JCTree.JCSynchronized) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCThrow) {
            JCTree.JCThrow castedStatement = (JCTree.JCThrow) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCTry) {
            JCTree.JCTry castedStatement = (JCTree.JCTry) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCVariableDecl) {
            JCTree.JCVariableDecl castedStatement = (JCTree.JCVariableDecl) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCWhileLoop) {
            JCTree.JCWhileLoop castedStatement = (JCTree.JCWhileLoop) statement;
            normalizedStatements.add(statement);
        } else if (statement instanceof JCTree.JCYield) {
            JCTree.JCYield castedStatement = (JCTree.JCYield) statement;
            normalizedStatements.add(statement);
        }

        return normalizedStatements;
    }

    /**
     * Rearrange statements so that all parameters and return values in this expression are also passed to
     * Logging.recordEvent
     *
     * @param expression to be normalized
     * @return list of normalized statements which has the same effect
     */
    private NormalizedStatement normalizeExpression(JCTree.JCExpression expression, JavacNode javacNode) {
        JavacTreeMaker treeMaker = javacNode.getTreeMaker();

        NormalizedStatement normalizedExpressions = new NormalizedStatement();
        if (expression instanceof JCTree.JCAnnotatedType) {
            JCTree.JCAnnotatedType castedExpression = (JCTree.JCAnnotatedType) expression;
        } else if (expression instanceof JCTree.JCAnnotation) {
            JCTree.JCAnnotation castedExpression = (JCTree.JCAnnotation) expression;
        } else if (expression instanceof JCTree.JCArrayAccess) {
            JCTree.JCArrayAccess castedExpression = (JCTree.JCArrayAccess) expression;
        } else if (expression instanceof JCTree.JCArrayTypeTree) {
            JCTree.JCArrayTypeTree castedExpression = (JCTree.JCArrayTypeTree) expression;
        } else if (expression instanceof JCTree.JCAssign) {

//            normalizedExpressions.addStatement(expression);

            JCTree.JCAssign assignExpression = (JCTree.JCAssign) expression;
            JCTree.JCExpression lhs = assignExpression.getVariable();
            JCTree.JCExpression rhs = assignExpression.getExpression();

        } else if (expression instanceof JCTree.JCAssignOp) {
            JCTree.JCAssignOp castedExpression = (JCTree.JCAssignOp) expression;
        } else if (expression instanceof JCTree.JCBinary) {
            JCTree.JCBinary castedExpression = (JCTree.JCBinary) expression;
        } else if (expression instanceof JCTree.JCConditional) {
            JCTree.JCConditional castedExpression = (JCTree.JCConditional) expression;
        } else if (expression instanceof JCTree.JCErroneous) {
            JCTree.JCErroneous castedExpression = (JCTree.JCErroneous) expression;
        } else if (expression instanceof JCTree.JCFieldAccess) {
            JCTree.JCFieldAccess castedExpression = (JCTree.JCFieldAccess) expression;
        } else if (expression instanceof JCTree.JCFunctionalExpression) {
            JCTree.JCFunctionalExpression castedExpression = (JCTree.JCFunctionalExpression) expression;
        } else if (expression instanceof JCTree.JCIdent) {
            JCTree.JCIdent castedExpression = (JCTree.JCIdent) expression;
        } else if (expression instanceof JCTree.JCInstanceOf) {
            JCTree.JCInstanceOf castedExpression = (JCTree.JCInstanceOf) expression;
        } else if (expression instanceof JCTree.JCLambda) {
            JCTree.JCLambda castedExpression = (JCTree.JCLambda) expression;
        } else if (expression instanceof JCTree.JCLiteral) {
            JCTree.JCLiteral castedExpression = (JCTree.JCLiteral) expression;
        } else if (expression instanceof JCTree.JCMemberReference) {
            JCTree.JCMemberReference castedExpression = (JCTree.JCMemberReference) expression;
        } else if (expression instanceof JCTree.JCMethodInvocation) {
            JCTree.JCMethodInvocation castedExpression = (JCTree.JCMethodInvocation) expression;
        } else if (expression instanceof JCTree.JCNewArray) {
            JCTree.JCNewArray castedExpression = (JCTree.JCNewArray) expression;
        } else if (expression instanceof JCTree.JCNewClass) {
            JCTree.JCNewClass castedExpression = (JCTree.JCNewClass) expression;
        } else if (expression instanceof JCTree.JCOperatorExpression) {
            JCTree.JCOperatorExpression castedExpression = (JCTree.JCOperatorExpression) expression;
        } else if (expression instanceof JCTree.JCParens) {
            JCTree.JCParens castedExpression = (JCTree.JCParens) expression;
        } else if (expression instanceof JCTree.JCPolyExpression) {
            JCTree.JCPolyExpression castedExpression = (JCTree.JCPolyExpression) expression;
        } else if (expression instanceof JCTree.JCPrimitiveTypeTree) {
            JCTree.JCPrimitiveTypeTree castedExpression = (JCTree.JCPrimitiveTypeTree) expression;
        } else if (expression instanceof JCTree.JCSwitchExpression) {
            JCTree.JCSwitchExpression castedExpression = (JCTree.JCSwitchExpression) expression;
        } else if (expression instanceof JCTree.JCTypeApply) {
            JCTree.JCTypeApply castedExpression = (JCTree.JCTypeApply) expression;
        } else if (expression instanceof JCTree.JCTypeCast) {
            JCTree.JCTypeCast castedExpression = (JCTree.JCTypeCast) expression;
        } else if (expression instanceof JCTree.JCTypeIntersection) {
            JCTree.JCTypeIntersection castedExpression = (JCTree.JCTypeIntersection) expression;
        } else if (expression instanceof JCTree.JCTypeUnion) {
            JCTree.JCTypeUnion castedExpression = (JCTree.JCTypeUnion) expression;
        } else if (expression instanceof JCTree.JCUnary) {
            JCTree.JCUnary castedExpression = (JCTree.JCUnary) expression;
        } else if (expression instanceof JCTree.JCWildcard) {
            JCTree.JCWildcard castedExpression = (JCTree.JCWildcard) expression;
        } else if (expression instanceof JCTree.LetExpr) {
            JCTree.LetExpr castedExpression = (JCTree.LetExpr) expression;
        }


        return normalizedExpressions;
    }

    private JCTree.JCExpressionStatement createLogStatement(JavacNode methodNode, String variableToRecord, int dataId) {
        JavacTreeMaker treeMaker = methodNode.getTreeMaker();
        JCTree.JCExpression printlnMethod = JavacHandlerUtil.chainDotsString(methodNode,
                "io.unlogged.logging.Logging.recordEvent");

        return treeMaker.Exec(
                treeMaker.Apply(
                        List.<JCTree.JCExpression>nil(),
                        printlnMethod,
                        List.<JCTree.JCExpression>of(
                                treeMaker.Ident(methodNode.toName(variableToRecord)),
                                treeMaker.Literal(dataId)
                        )
                )
        );
    }

    private String createMethodDescriptor(JavacNode methodNode) {
        return "(IL)Ljava.lang.Integer;";
    }

    public Map<JCTree, ClassInfo> getClassRoots() {
        return classRoots;
    }

    public Map<ClassInfo, JavacNode> getClassNodeMap() {
        return classJavacNodeMap;
    }

    public Map<ClassInfo, java.util.List<MethodInfo>> getMethodMap() {
        return classMethodInfoList;
    }

    public Map<ClassInfo, java.util.List<DataInfo>> getClassDataInfoList() {
        return classDataInfoList;
    }
}