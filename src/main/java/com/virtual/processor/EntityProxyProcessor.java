package com.virtual.processor;

import com.virtual.api.Entity;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 编译期注解处理器：扫描 Entity 子类，自动生成 _XXX 代理类。
 *
 * <p>由 javac 在编译期自动调用（通过 META-INF/services 注册）。
 */
@SupportedAnnotationTypes("com.virtual.api.Entity")
@SuppressWarnings("unused")
public class EntityProxyProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    // ---- APT 入口（由 javac 调用） ----

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;
            TypeElement typeElement = (TypeElement) element;

            if (shouldSkip(typeElement)) continue;

            String packageName = processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
            String className = typeElement.getSimpleName().toString();
            String proxyClassName = "_" + className;

            List<FieldMeta> fields = collectFieldMeta(typeElement);
            String code = generateCode(packageName, className, proxyClassName, fields);

            try {
                JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(
                        packageName + "." + proxyClassName, typeElement);
                try (Writer writer = sourceFile.openWriter()) {
                    writer.write(code);
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "生成 " + proxyClassName + " 失败: " + e.getMessage());
            }
        }
        return false;
    }

    // ---- 跳过判断 ----

    private boolean shouldSkip(TypeElement type) {
        if (type.getQualifiedName().toString().equals("com.virtual.entity.Entity")) return true;
        if (type.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) return true;
        if (type.getSimpleName().toString().startsWith("_")) return true;
        if (type.getQualifiedName().toString().startsWith("com.virtual.entity.Visit")) return true;

        // 手动遍历超类链，避免 TypeUtils.isSubtype 跨编译单元判断不一致的问题
        TypeMirror superclass = type.getSuperclass();
        while (superclass instanceof DeclaredType dt) {
            TypeElement superElem = (TypeElement) dt.asElement();
            if (superElem.getQualifiedName().contentEquals("com.virtual.TableDefine")) {
                return false; // 找到 TableDefine，不跳过
            }
            superclass = superElem.getSuperclass();
        }
        return true; // 未找到 TableDefine，跳过
    }

    // ---- 字段收集 ----

    private List<FieldMeta> collectFieldMeta(TypeElement type) {
        List<FieldMeta> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            Set<javax.lang.model.element.Modifier> mods = enclosed.getModifiers();
            if (mods.contains(javax.lang.model.element.Modifier.STATIC)
                    || mods.contains(javax.lang.model.element.Modifier.FINAL)) continue;

            VariableElement field = (VariableElement) enclosed;
            String name = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();
            FieldCategory cat = categorize(fieldType);
            result.add(new FieldMeta(name, cat, fieldType));
        }
        return result;
    }

    private FieldCategory categorize(TypeMirror type) {
        if (type.getKind() == TypeKind.INT) return FieldCategory.INT;
        if (type.getKind() == TypeKind.LONG) return FieldCategory.LONG;

        TypeElement stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String");
        if (stringType != null && processingEnv.getTypeUtils().isSameType(type, stringType.asType())) {
            return FieldCategory.STRING;
        }

        if (type instanceof DeclaredType dt) {
            String qn = ((TypeElement) dt.asElement()).getQualifiedName().toString();
            if (qn.equals("java.util.Map")) return FieldCategory.MAP;
            if (qn.equals("java.util.List")) return FieldCategory.LIST;
            if (qn.equals("java.util.Set")) return FieldCategory.SET;

            TypeElement entityType = processingEnv.getElementUtils().getTypeElement("com.virtual.entity.Entity");
            if (entityType != null && processingEnv.getTypeUtils().isAssignable(type, entityType.asType())) {
                return FieldCategory.ENTITY;
            }
        }
        return FieldCategory.OTHER;
    }

    // ---- 代码生成 ----

    private static String generateCode(String packageName, String className,
                                        String proxyClassName, List<FieldMeta> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");

        boolean hasVisit = false, hasMap = false, hasList = false, hasSet = false;
        for (FieldMeta f : fields) {
            switch (f.category) {
                case MAP -> { hasVisit = true; hasMap = true; }
                case LIST -> { hasVisit = true; hasList = true; }
                case SET -> { hasVisit = true; hasSet = true; }
            }
        }

        sb.append("import com.virtual.Log.Log;\n");
        sb.append("import com.virtual.Log.SimpleLog;\n");
        sb.append("import com.virtual.TransactionImpl;\n");
        if (hasVisit) sb.append("import com.virtual.Log.VisitEntityLog;\n");
        if (hasMap) { sb.append("import com.virtual.entity.VisitMapEntity;\n"); sb.append("import java.util.Map;\n"); }
        if (hasList) { sb.append("import com.virtual.entity.VisitListEntity;\n"); sb.append("import java.util.List;\n"); }
        if (hasSet) { sb.append("import com.virtual.entity.VisitSetEntity;\n"); sb.append("import java.util.Set;\n"); }
        sb.append("\n");

        sb.append("/** 由 EntityProxyProcessor 自动生成的代理类。 */\n");
        sb.append("public class ").append(proxyClassName).append(" extends ").append(className).append(" {\n\n");

        for (FieldMeta f : fields) {
            generateGetter(sb, f);
            sb.append("\n");
            generateSetter(sb, f);
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static void generateGetter(StringBuilder sb, FieldMeta f) {
        String getter = "get" + capitalize(f.name);
        String t = typeName(f.genericType);

        switch (f.category) {
            case INT, LONG, STRING, ENTITY, OTHER -> {
                sb.append("    @Override\n");
                sb.append("    public ").append(t).append(" ").append(getter).append("() {\n");
                sb.append("        TransactionImpl transaction = TransactionImpl.checkAndGet();\n");
                sb.append("        Log<").append(box(f, t)).append("> log = transaction.getLog(this, \"").append(f.name).append("\");\n");
                sb.append("        return log == null ? super.").append(getter).append("() : log.getValue();\n");
                sb.append("    }\n");
            }
            case MAP, LIST, SET -> {
                String vt = visitType(f.category);
                String va = visitArgs(f.genericType);
                sb.append("    @Override\n");
                sb.append("    public ").append(t).append(" ").append(getter).append("() {\n");
                sb.append("        TransactionImpl transaction = TransactionImpl.checkAndGet();\n");
                sb.append("        Log<").append(t).append("> log = transaction.getLog(this, \"").append(f.name).append("\");\n");
                sb.append("        if (log != null) {\n");
                sb.append("            return log.getValue();\n");
                sb.append("        } else if (super.").append(getter).append("() == null) {\n");
                sb.append("            return null;\n");
                sb.append("        } else {\n");
                sb.append("            ").append(vt).append(va).append(" vEntity = new ").append(vt).append("<>(super.").append(getter).append("());\n");
                sb.append("            vEntity.setRoot(this.getRoot());\n");
                sb.append("            transaction.log(this, \"").append(f.name).append("\", new VisitEntityLog<>(vEntity, super::set").append(capitalize(f.name)).append("));\n");
                sb.append("            return vEntity;\n");
                sb.append("        }\n");
                sb.append("    }\n");
            }
        }
    }

    private static void generateSetter(StringBuilder sb, FieldMeta f) {
        String setter = "set" + capitalize(f.name);
        String t = typeName(f.genericType);
        String n = f.name;

        switch (f.category) {
            case INT, LONG, STRING, OTHER -> {
                sb.append("    @Override\n");
                sb.append("    public void ").append(setter).append("(").append(t).append(" ").append(n).append(") {\n");
                sb.append("        TransactionImpl transaction = TransactionImpl.checkAndGet();\n");
                sb.append("        transaction.log(this, \"").append(n).append("\", new SimpleLog<>(").append(n).append(", super::").append(setter).append("));\n");
                sb.append("    }\n");
            }
            case ENTITY -> {
                sb.append("    @Override\n");
                sb.append("    public void ").append(setter).append("(").append(t).append(" ").append(n).append(") {\n");
                sb.append("        ").append(n).append(".checkValid(this.getRoot());\n");
                sb.append("        ").append(n).append(".setRoot(this.getRoot());\n");
                sb.append("        TransactionImpl transaction = TransactionImpl.checkAndGet();\n");
                sb.append("        transaction.log(this, \"").append(n).append("\", new SimpleLog<>(").append(n).append(", super::").append(setter).append("));\n");
                sb.append("    }\n");
            }
            case MAP, LIST, SET -> {
                String vt = visitType(f.category);
                String va = visitArgs(f.genericType);
                sb.append("    @Override\n");
                sb.append("    public void ").append(setter).append("(").append(t).append(" ").append(n).append(") {\n");
                sb.append("        ").append(vt).append(va).append(" vEntity = new ").append(vt).append("<>(").append(n).append(");\n");
                sb.append("        vEntity.setRoot(this.getRoot());\n");
                sb.append("        TransactionImpl transaction = TransactionImpl.checkAndGet();\n");
                sb.append("        transaction.log(this, \"").append(n).append("\", new VisitEntityLog<>(vEntity, super::").append(setter).append("));\n");
                sb.append("    }\n");
            }
        }
    }

    // ---- 类型名工具 ----

    /** 从 TypeMirror 生成 Java 类型名（支持泛型递归） */
    private static String typeName(TypeMirror type) {
        if (type instanceof DeclaredType dt) {
            TypeElement elem = (TypeElement) dt.asElement();
            String name = elem.getSimpleName().toString();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                StringBuilder sb = new StringBuilder(name).append("<");
                for (int i = 0; i < typeArgs.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeName(typeArgs.get(i)));
                }
                sb.append(">");
                return sb.toString();
            }
            return name;
        }
        return switch (type.getKind()) {
            case INT -> "int";
            case LONG -> "long";
            case BOOLEAN -> "boolean";
            case SHORT -> "short";
            case BYTE -> "byte";
            case CHAR -> "char";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            default -> type.toString();
        };
    }

    private static String visitType(FieldCategory cat) {
        return switch (cat) {
            case MAP -> "VisitMapEntity";
            case LIST -> "VisitListEntity";
            case SET -> "VisitSetEntity";
            default -> throw new IllegalArgumentException();
        };
    }

    /** 提取泛型参数，用于 Visit 类型声明 */
    private static String visitArgs(TypeMirror type) {
        if (type instanceof DeclaredType dt) {
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (typeArgs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("<");
            for (int i = 0; i < typeArgs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeName(typeArgs.get(i)));
            }
            sb.append(">");
            return sb.toString();
        }
        return "";
    }

    private static String box(FieldMeta f, String t) {
        return switch (f.category) {
            case INT -> "Integer";
            case LONG -> "Long";
            default -> t;
        };
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- 内部类型 ----

    enum FieldCategory { INT, LONG, STRING, ENTITY, MAP, LIST, SET, OTHER }

    static class FieldMeta {
        final String name;
        final FieldCategory category;
        final TypeMirror genericType;

        FieldMeta(String name, FieldCategory category, TypeMirror genericType) {
            this.name = name;
            this.category = category;
            this.genericType = genericType;
        }
    }
}
