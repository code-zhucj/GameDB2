package com.virtual.processor;

import com.virtual.api.Entity;
import com.virtual.api.Id;

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
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
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

            boolean isTableDefine = isTableDefineSubclass(typeElement);
            FieldMeta idField = null;

            if (isTableDefine) {
                // TableDefine 子类必须标注 @Id
                List<FieldMeta> idFields = fields.stream().filter(f -> f.isId).toList();
                if (idFields.isEmpty()) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            className + " 未标注 @Id 主键字段");
                    continue;
                }
                if (idFields.size() > 1) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            className + " 存在多个 @Id 主键字段");
                    continue;
                }
                idField = idFields.get(0);
            }

            // 生成代理类
            String proxyCode = generateProxyCode(packageName, className, proxyClassName, fields, idField, isTableDefine);
            try {
                JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(
                        packageName + "." + proxyClassName, typeElement);
                try (Writer writer = sourceFile.openWriter()) {
                    writer.write(proxyCode);
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "生成 " + proxyClassName + " 失败: " + e.getMessage());
            }

            // TableDefine 子类写入独立的标记文件，互不覆盖
            if (isTableDefine) {
                try {
                    String fqn = packageName + "." + proxyClassName;
                    FileObject file = processingEnv.getFiler().createResource(
                            StandardLocation.CLASS_OUTPUT, "", "META-INF/tables/" + fqn);
                    file.openWriter().close(); // 空文件即可，仅用作标记
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "写入标记文件失败: " + e.getMessage());
                }
            }
        }
        return false;
    }

    // ---- 跳过判断 ----

    private boolean shouldSkip(TypeElement type) {
        if (type.getQualifiedName().toString().equals("com.virtual.entity.Entity")) return true;
        if (type.getModifiers().contains(javax.lang.model.element.Modifier.FINAL)) return true;
        if (type.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) return true;

        // 手动遍历超类链，避免 TypeUtils.isSubtype 跨编译单元判断不一致的问题
        TypeMirror superclass = type.getSuperclass();
        while (superclass instanceof DeclaredType dt) {
            TypeElement superElem = (TypeElement) dt.asElement();
            if (superElem.getQualifiedName().contentEquals("com.virtual.entity.Entity")) {
                return false; // 找到 Entity，不跳过
            }
            superclass = superElem.getSuperclass();
        }
        return true; // 未找到 Entity，跳过
    }

    /** 判断是否继承 TableDefine（需要生成 primaryKey） */
    private boolean isTableDefineSubclass(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        while (superclass instanceof DeclaredType dt) {
            TypeElement superElem = (TypeElement) dt.asElement();
            if (superElem.getQualifiedName().contentEquals("com.virtual.TableDefine")) {
                return true;
            }
            superclass = superElem.getSuperclass();
        }
        return false;
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
            boolean isId = field.getAnnotation(Id.class) != null;
            result.add(new FieldMeta(name, cat, fieldType, isId));
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

    private static String generateProxyCode(String packageName, String className,
                                        String proxyClassName, List<FieldMeta> fields,
                                        FieldMeta idField, boolean isTableDefine) {
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

        if (isTableDefine) sb.append("import com.virtual.Tables;\n");
        sb.append("import com.virtual.Log.Log;\n");
        sb.append("import com.virtual.Log.SimpleLog;\n");
        sb.append("import com.virtual.TransactionImpl;\n");
        if (hasVisit) sb.append("import com.virtual.Log.VisitEntityLog;\n");
        if (hasMap) { sb.append("import com.virtual.entity.VisitMapEntity;\n"); sb.append("import java.util.Map;\n"); }
        if (hasList) { sb.append("import com.virtual.entity.VisitListEntity;\n"); sb.append("import java.util.List;\n"); }
        if (hasSet) { sb.append("import com.virtual.entity.VisitSetEntity;\n"); sb.append("import java.util.Set;\n"); }
        // encode/decode 导入
        sb.append("import com.virtual.codec.Writer;\n");
        sb.append("import com.virtual.codec.Reader;\n");
        boolean hasCollInDecode = hasMap || hasList || hasSet;
        if (hasList || hasSet) sb.append("import java.util.ArrayList;\n");
        if (hasSet) sb.append("import java.util.HashSet;\n");
        if (hasMap) sb.append("import java.util.HashMap;\n");
        sb.append("\n");

        sb.append("/** 由 EntityProxyProcessor 自动生成的代理类。 */\n");
        sb.append("public final class ").append(proxyClassName).append(" extends ").append(className).append(" {\n\n");

        // TableDefine 子类：static 块注册到 Tables
        if (isTableDefine) {
            sb.append("    static {\n");
            sb.append("        Tables.register(").append(className).append(".class, ")
                    .append(proxyClassName).append(".class, ").append(proxyClassName).append("::new);\n");
            sb.append("    }\n\n");
        }

        // TableDefine 子类：生成 primaryKey()，使用 @Id 字段的 getter
        if (idField != null) {
            String idGetter = "get" + capitalize(idField.name);
            sb.append("    @Override\n");
            sb.append("    public Comparable<?> primaryKey() {\n");
            sb.append("        return ").append(idGetter).append("();\n");
            sb.append("    }\n\n");
        }

        for (FieldMeta f : fields) {
            generateGetter(sb, f);
            sb.append("\n");
            generateSetter(sb, f);
            sb.append("\n");
        }

        // encode / decode 覆写
        generateEncode(sb, fields, idField);
        generateDecode(sb, fields, idField);

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

    // ---- encode / decode 生成 ----

    private static void generateEncode(StringBuilder sb, List<FieldMeta> fields, FieldMeta idField) {
        sb.append("    @Override\n");
        sb.append("    public void encode(Writer writer) {\n");
        sb.append("        writer.writeStartDocument();\n");

        for (FieldMeta f : fields) {
            String fieldName = f.isId ? "_id" : f.name;
            String getter = "get" + capitalize(f.name);
            switch (f.category) {
                case INT -> sb.append("        writer.writeInt32(\"").append(fieldName)
                        .append("\", super.").append(getter).append("());\n");
                case LONG -> sb.append("        writer.writeInt64(\"").append(fieldName)
                        .append("\", super.").append(getter).append("());\n");
                case STRING -> {
                    sb.append("        {\n");
                    sb.append("            String v = super.").append(getter).append("();\n");
                    sb.append("            if (v != null) writer.writeString(\"").append(fieldName)
                            .append("\", v);\n");
                    sb.append("        }\n");
                }
                case ENTITY -> {
                    String t = typeName(f.genericType);
                    sb.append("        {\n");
                    sb.append("            ").append(t).append(" v = super.").append(getter).append("();\n");
                    sb.append("            if (v != null) {\n");
                    sb.append("                writer.writeName(\"").append(fieldName).append("\");\n");
                    sb.append("                v.encode(writer);\n");
                    sb.append("            }\n");
                    sb.append("        }\n");
                }
                case LIST, SET -> generateCollectionEncode(sb, f, getter);
                case MAP -> generateMapEncode(sb, f, getter);
                case OTHER -> { /* skip */ }
            }
        }

        sb.append("        writer.writeEndDocument();\n");
        sb.append("    }\n\n");
    }

    /** 为 List/Set 字段生成 encode 代码 */
    private static void generateCollectionEncode(StringBuilder sb, FieldMeta f, String getter) {
        CollectionElem elem = getCollectionElem(f.genericType);
        String collType = typeName(f.genericType);
        sb.append("        {\n");
        sb.append("            ").append(collType).append(" coll = super.").append(getter).append("();\n");
        sb.append("            if (coll != null && !coll.isEmpty()) {\n");
        String fieldName = f.isId ? "_id" : f.name;
        sb.append("                writer.writeStartArray(\"").append(fieldName).append("\");\n");
        if (elem != null) {
            switch (elem.category()) {
                case INT -> sb.append("                for (int it : (Iterable<Integer>) coll) writer.writeInt32(it);\n");
                case LONG -> sb.append("                for (long it : (Iterable<Long>) coll) writer.writeInt64(it);\n");
                case STRING -> sb.append("                for (String it : (Iterable<String>) coll) writer.writeString(it);\n");
                case ENTITY -> {
                    sb.append("                for (").append(elem.typeName()).append(" it : (Iterable<")
                            .append(elem.typeName()).append(">) coll) {\n");
                    sb.append("                    it.encode(writer);\n");
                    sb.append("                }\n");
                }
                default -> sb.append("                // unsupported element type\n");
            }
        }
        sb.append("                writer.writeEndArray();\n");
        sb.append("            }\n");
        sb.append("        }\n");
    }

    /** 为 Map 字段生成 encode 代码 */
    private static void generateMapEncode(StringBuilder sb, FieldMeta f, String getter) {
        MapElem map = getMapElem(f.genericType);
        String collType = typeName(f.genericType);
        sb.append("        {\n");
        sb.append("            ").append(collType).append(" map = super.").append(getter).append("();\n");
        sb.append("            if (map != null && !map.isEmpty()) {\n");
        String mapFieldName = f.isId ? "_id" : f.name;
        sb.append("                writer.writeStartArray(\"").append(mapFieldName).append("\");\n");
        if (map != null) {
            sb.append("                for (java.util.Map.Entry<?,?> e : map.entrySet()) {\n");
            sb.append("                    writer.writeStartDocument();\n");
            writeMapEntry(sb, map.key(), "e.getKey()", false);
            writeMapEntry(sb, map.value(), "e.getValue()", true);
            sb.append("                    writer.writeEndDocument();\n");
            sb.append("                }\n");
        }
        sb.append("                writer.writeEndArray();\n");
        sb.append("            }\n");
        sb.append("        }\n");
    }

    private static void writeMapEntry(StringBuilder sb, Object elem, String expr, boolean isValue) {
        String name = isValue ? "v" : "k";
        if (elem instanceof CollectionElem ce) {
            switch (ce.category()) {
                case INT -> sb.append("                    writer.writeInt32(\"").append(name)
                        .append("\", (Integer)").append(expr).append(");\n");
                case LONG -> sb.append("                    writer.writeInt64(\"").append(name)
                        .append("\", (Long)").append(expr).append(");\n");
                case STRING -> sb.append("                    writer.writeString(\"").append(name)
                        .append("\", (String)").append(expr).append(");\n");
                case ENTITY -> {
                    sb.append("                    writer.writeName(\"").append(name).append("\");\n");
                    sb.append("                    ((").append(ce.typeName()).append(")").append(expr).append(").encode(writer);\n");
                }
                default -> sb.append("                    // unsupported\n");
            }
        }
    }

    private static void generateDecode(StringBuilder sb, List<FieldMeta> fields, FieldMeta idField) {
        sb.append("    @Override\n");
        sb.append("    public void decode(Reader reader) {\n");
        sb.append("        reader.readStartDocument();\n");
        sb.append("        while (reader.readBsonType() != Reader.END_OF_DOCUMENT) {\n");
        sb.append("            String fieldName = reader.readName();\n");
        sb.append("            switch (fieldName) {\n");

        for (FieldMeta f : fields) {
            String fieldName = f.isId ? "_id" : f.name;
            sb.append("                case \"").append(fieldName).append("\": ");
            String setter = "set" + capitalize(f.name);
            switch (f.category) {
                case INT -> sb.append("super.").append(setter).append("(reader.readInt32()); break;\n");
                case LONG -> sb.append("super.").append(setter).append("(reader.readInt64()); break;\n");
                case STRING -> sb.append("super.").append(setter).append("(reader.readString()); break;\n");
                case ENTITY -> {
                    String tn = typeName(f.genericType);
                    sb.append("{\n");
                    sb.append("                    _").append(tn).append(" v = new _").append(tn).append("();\n");
                    sb.append("                    v.decode(reader);\n");
                    sb.append("                    super.").append(setter).append("(v);\n");
                    sb.append("                }\n");
                    sb.append("                break;\n");
                }
                case LIST, SET -> generateCollectionDecode(sb, f, setter);
                case MAP -> generateMapDecode(sb, f, setter);
                case OTHER -> sb.append("reader.skipValue(); break;\n");
            }
        }

        sb.append("                default: reader.skipValue(); break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        reader.readEndDocument();\n");
        sb.append("    }\n\n");
    }

    private static void generateCollectionDecode(StringBuilder sb, FieldMeta f, String setter) {
        CollectionElem elem = getCollectionElem(f.genericType);
        String elemType = elem != null ? elem.typeName() : "Object";
        sb.append("{\n");
        sb.append("                    reader.readStartArray();\n");
        if (f.category == FieldCategory.LIST) {
            sb.append("                    java.util.ArrayList<").append(elemType)
                    .append("> list = new ArrayList<>();\n");
        } else {
            sb.append("                    java.util.HashSet<").append(elemType)
                    .append("> set = new HashSet<>();\n");
        }
        sb.append("                    while (reader.readBsonType() != Reader.END_OF_DOCUMENT) {\n");
        sb.append("                        reader.readName(); // skip array index\n");
        if (elem != null) {
            switch (elem.category()) {
                case INT -> {
                    String coll = f.category == FieldCategory.LIST ? "list" : "set";
                    sb.append("                        ").append(coll).append(".add(reader.readInt32());\n");
                }
                case LONG -> {
                    String coll = f.category == FieldCategory.LIST ? "list" : "set";
                    sb.append("                        ").append(coll).append(".add(reader.readInt64());\n");
                }
                case STRING -> {
                    String coll = f.category == FieldCategory.LIST ? "list" : "set";
                    sb.append("                        ").append(coll).append(".add(reader.readString());\n");
                }
                case ENTITY -> {
                    sb.append("                        _").append(elemType).append(" elem = new _")
                            .append(elemType).append("();\n");
                    sb.append("                        elem.decode(reader);\n");
                    String coll = f.category == FieldCategory.LIST ? "list" : "set";
                    sb.append("                        ").append(coll).append(".add(elem);\n");
                }
                default -> sb.append("                        reader.skipValue();\n");
            }
        } else {
            sb.append("                        reader.skipValue();\n");
        }
        sb.append("                    }\n");
        sb.append("                    reader.readEndArray();\n");
        String coll = f.category == FieldCategory.LIST ? "list" : "set";
        sb.append("                    super.").append(setter).append("(").append(coll).append(");\n");
        sb.append("                }\n");
        sb.append("                break;\n");
    }

    private static void generateMapDecode(StringBuilder sb, FieldMeta f, String setter) {
        MapElem map = getMapElem(f.genericType);
        String keyType = map != null && map.key() != null ? map.key().typeName() : "Object";
        String valType = map != null && map.value() != null ? map.value().typeName() : "Object";
        sb.append("{\n");
        sb.append("                    reader.readStartArray();\n");
        sb.append("                    HashMap<").append(keyType).append(",").append(valType)
                .append("> m = new HashMap<>();\n");
        sb.append("                    while (reader.readBsonType() != Reader.END_OF_DOCUMENT) {\n");
        sb.append("                        reader.readStartDocument();\n");
        sb.append("                        ").append(keyType).append(" k = null;\n");
        sb.append("                        ").append(valType).append(" v = null;\n");
        sb.append("                        while (reader.readBsonType() != Reader.END_OF_DOCUMENT) {\n");
        sb.append("                            String entryKey = reader.readName();\n");
        sb.append("                            switch (entryKey) {\n");
        sb.append("                                case \"k\": ");
        readMapValue(sb, map != null ? map.key() : null, "k");
        sb.append("                                case \"v\": ");
        readMapValue(sb, map != null ? map.value() : null, "v");
        sb.append("                                default: reader.skipValue(); break;\n");
        sb.append("                            }\n");
        sb.append("                        }\n");
        sb.append("                        reader.readEndDocument();\n");
        sb.append("                        if (k != null) m.put(k, v);\n");
        sb.append("                    }\n");
        sb.append("                    reader.readEndArray();\n");
        sb.append("                    super.").append(setter).append("(m);\n");
        sb.append("                }\n");
        sb.append("                break;\n");
    }

    private static void readMapValue(StringBuilder sb, Object elem, String var) {
        if (elem instanceof CollectionElem ce) {
            switch (ce.category()) {
                case INT -> sb.append(var).append(" = reader.readInt32(); break;\n");
                case LONG -> sb.append(var).append(" = reader.readInt64(); break;\n");
                case STRING -> sb.append(var).append(" = reader.readString(); break;\n");
                case ENTITY -> {
                    sb.append("{\n");
                    sb.append("                                _").append(ce.typeName()).append(" obj = new _")
                            .append(ce.typeName()).append("();\n");
                    sb.append("                                obj.decode(reader);\n");
                    sb.append("                                ").append(var).append(" = obj;\n");
                    sb.append("                            }\n");
                    sb.append("                            break;\n");
                }
                default -> sb.append("reader.skipValue(); break;\n");
            }
        } else {
            sb.append("reader.skipValue(); break;\n");
        }
    }

    // ---- 集合元素类型提取 ----

    /** List/Set 的元素类型信息 */
    private static CollectionElem getCollectionElem(TypeMirror collType) {
        if (collType instanceof javax.lang.model.type.DeclaredType dt) {
            List<? extends TypeMirror> args = dt.getTypeArguments();
            if (args.size() >= 1) {
                return categoryOf(args.get(0));
            }
        }
        return null;
    }

    /** Map 的 key/value 类型信息 */
    private static MapElem getMapElem(TypeMirror collType) {
        if (collType instanceof javax.lang.model.type.DeclaredType dt) {
            List<? extends TypeMirror> args = dt.getTypeArguments();
            if (args.size() >= 2) {
                return new MapElem(categoryOf(args.get(0)), categoryOf(args.get(1)));
            }
        }
        return null;
    }

    private static CollectionElem categoryOf(TypeMirror type) {
        if (type.getKind() == javax.lang.model.type.TypeKind.INT)
            return new CollectionElem(FieldCategory.INT, "Integer");
        if (type.getKind() == javax.lang.model.type.TypeKind.LONG)
            return new CollectionElem(FieldCategory.LONG, "Long");
        if (type instanceof javax.lang.model.type.DeclaredType dt) {
            javax.lang.model.element.TypeElement elem =
                    (javax.lang.model.element.TypeElement) dt.asElement();
            if (elem.getQualifiedName().contentEquals("java.lang.String"))
                return new CollectionElem(FieldCategory.STRING, "String");
            if (elem.getQualifiedName().contentEquals("java.lang.Integer"))
                return new CollectionElem(FieldCategory.INT, "Integer");
            if (elem.getQualifiedName().contentEquals("java.lang.Long"))
                return new CollectionElem(FieldCategory.LONG, "Long");
            return new CollectionElem(FieldCategory.ENTITY, elem.getSimpleName().toString());
        }
        return null;
    }

    private record CollectionElem(FieldCategory category, String typeName) {}
    private record MapElem(CollectionElem key, CollectionElem value) {}

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
        final boolean isId;

        FieldMeta(String name, FieldCategory category, TypeMirror genericType, boolean isId) {
            this.name = name;
            this.category = category;
            this.genericType = genericType;
            this.isId = isId;
        }
    }
}
