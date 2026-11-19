package dev.talend.idea;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads the persisted Talend model. No context parameter values are used. */
final class TalendFragments {
    enum Kind { STATEMENTS, MEMBERS, IMPORTS, EXPRESSION }

    record Fragment(XmlTag component, XmlAttribute attribute, Kind kind, String label, String expectedType) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final String TALEND_NS = "platform:/resource/org.talend.model/model/TalendFile.xsd";
    private static final String JOBLET_NS = "http://www.talend.com/joblet.ecore";
    private static final String TEST_NS = "http://www.talend.com/testcontainer.ecore";

    static XmlTag job(XmlFile file) {
        if (!file.getName().endsWith(".item")) return null;
        XmlTag root = file.getRootTag();
        if (root == null) return null;
        if (is(root, "ProcessType", TALEND_NS) || is(root, "TestContainer", TEST_NS)) return root;
        if (is(root, "XMI", "http://www.omg.org/XMI")) {
            for (XmlTag child : root.getSubTags()) {
                if (is(child, "JobletProcess", JOBLET_NS)) return child;
            }
        }
        return null;
    }

    private static boolean is(XmlTag tag, String name, String namespace) {
        return name.equals(tag.getLocalName()) && namespace.equals(tag.getNamespace());
    }

    static List<Fragment> find(XmlTag job) {
        List<Fragment> result = new ArrayList<>();
        for (XmlTag node : job.findSubTags("node")) {
            String name = value(node, "componentName");
            if (name == null) continue;
            String id = parameterValue(node, "UNIQUE_NAME");
            String label = id == null || id.isEmpty() ? name : id + " (" + name + ")";
            switch (name) {
                case "tJava", "tJavaRow" -> {
                    addParameter(result, node, "CODE", Kind.STATEMENTS, label);
                    if (name.equals("tJava")) addParameter(result, node, "CLASS_CODE", Kind.MEMBERS, label);
                    addParameter(result, node, "IMPORT", Kind.IMPORTS, label);
                }
                case "tMap" -> addMapperFragments(result, job, node, label);
                default -> { }
            }
        }
        return result;
    }

    private static void addParameter(List<Fragment> result, XmlTag node, String name, Kind kind, String label) {
        XmlTag param = parameter(node, name);
        if (param != null) add(result, node, param, "value", kind, label + " / " + name, null);
    }

    private static void addMapperFragments(List<Fragment> result, XmlTag job, XmlTag node, String label) {
        XmlTag mapper = mapper(node);
        if (mapper == null) return;
        for (XmlTag table : mapper.getSubTags()) {
            String tableKind = table.getLocalName();
            if (!List.of("inputTables", "varTables", "outputTables").contains(tableKind)) continue;
            String name = tableName(table);
            String tableLabel = label + " / " + switch (tableKind) {
                case "inputTables" -> "Input ";
                case "varTables" -> "Variables ";
                default -> "Output ";
            } + name;
            Map<String, String> fields = mapperFields(job, node, table);
            add(result, node, table, "expressionFilter", Kind.EXPRESSION, tableLabel + " / filter", "boolean");
            for (XmlTag entry : table.findSubTags("mapperTableEntries")) {
                String column = value(entry, "name");
                if (column != null) {
                    add(result, node, entry, "expression", Kind.EXPRESSION, tableLabel + " / " + column, fields.get(column));
                }
            }
        }
    }

    private static void add(List<Fragment> result, XmlTag node, XmlTag tag, String attribute, Kind kind,
                            String label, String type) {
        XmlAttribute host = tag.getAttribute(attribute);
        if (host != null && FragmentXml.interior(host) != null) result.add(new Fragment(node, host, kind, label, type));
    }

    static Fragment atCaret(List<Fragment> fragments, int offset) {
        Fragment component = null;
        for (Fragment fragment : fragments) {
            TextRange range = fragment.attribute().getTextRange();
            if (range.containsOffset(offset)) return fragment;
            if (component == null && fragment.component().getTextRange().containsOffset(offset)) component = fragment;
        }
        return component;
    }

    static String parameterValue(XmlTag node, String name) {
        XmlTag parameter = parameter(node, name);
        return parameter == null ? null : value(parameter, "value");
    }

    private static XmlTag parameter(XmlTag tag, String name) {
        for (XmlTag parameter : tag.findSubTags("elementParameter")) {
            if (name.equals(value(parameter, "name"))) return parameter;
        }
        return null;
    }

    private static String value(XmlTag tag, String name) {
        XmlAttribute attribute = tag.getAttribute(name);
        return attribute == null ? null : attribute.getDisplayValue();
    }

    private static XmlTag mapper(XmlTag node) {
        for (XmlTag tag : node.getSubTags()) {
            if ("externalMapperData".equals(tag.getLocalName())) return tag;
            // EMF stores MapperData as the nodeData containment feature in .item files.
            if ("nodeData".equals(tag.getLocalName())) {
                String type = tag.getAttributeValue("type", "http://www.w3.org/2001/XMLSchema-instance");
                if ("MapperData".equals(type) || (type != null && type.endsWith(":MapperData"))) return tag;
            }
        }
        return null;
    }

    static Map<String, Map<String, String>> rows(XmlTag job, XmlTag node) {
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        if ("tJavaRow".equals(value(node, "componentName"))) {
            String id = parameterValue(node, "UNIQUE_NAME");
            XmlTag input = connection(job, "target", id, null);
            XmlTag output = connection(job, "source", id, null);
            boolean spark = spark(job);
            rows.put(spark ? "input" : "input_row", columns(input == null ? null : sourceSchema(job, input)));
            rows.put(spark ? (sparkFlatMap(job, node) ? "outputList" : "output") : "output_row",
                    columns(schema(node, output, null)));
        } else if ("tMap".equals(value(node, "componentName"))) {
            XmlTag mapper = mapper(node);
            if (mapper == null) return rows;
            for (XmlTag table : mapper.findSubTags("inputTables")) {
                rows.putIfAbsent(tableName(table), mapperFields(job, node, table));
            }
            for (XmlTag table : mapper.findSubTags("varTables")) {
                rows.putIfAbsent(tableName(table), mapperFields(job, node, table));
            }
        }
        return rows;
    }

    private static boolean spark(XmlTag job) {
        String framework = value(job, "framework");
        return "Spark".equals(framework) || "Spark Streaming".equals(framework);
    }

    static boolean sparkFlatMap(XmlTag job, XmlTag node) {
        return "tJavaRow".equals(value(node, "componentName")) && spark(job)
                && "FLATMAP".equals(parameterValue(node, "MAPTYPE"));
    }

    private static String tableName(XmlTag table) {
        String name = value(table, "name");
        return name == null ? ("varTables".equals(table.getLocalName()) ? "Var" : "") : name;
    }

    private static Map<String, String> mapperFields(XmlTag job, XmlTag node, XmlTag table) {
        String name = tableName(table);
        String id = parameterValue(node, "UNIQUE_NAME");
        XmlTag schema = switch (table.getLocalName()) {
            case "inputTables" -> {
                XmlTag input = connection(job, "target", id, name);
                yield input == null ? null : sourceSchema(job, input);
            }
            case "outputTables" -> schema(node, connection(job, "source", id, name), name);
            default -> null;
        };
        Map<String, String> fields = columns(schema);
        for (XmlTag entry : table.findSubTags("mapperTableEntries")) {
            String type = value(entry, "type");
            String column = value(entry, "name");
            if (identifier(column)) {
                fields.putIfAbsent(column, javaType(type, !"false".equals(value(entry, "nullable"))));
            }
        }
        return fields;
    }

    private static XmlTag connection(XmlTag job, String endpoint, String id, String row) {
        if (id == null) return null;
        for (XmlTag link : job.findSubTags("connection")) {
            if (!id.equals(value(link, endpoint)) || !rowConnector(value(link, "connectorName"))) continue;
            if (row == null || row.equals(value(link, "label")) || row.equals(parameterValue(link, "UNIQUE_NAME"))) return link;
        }
        return null;
    }

    private static boolean rowConnector(String connector) {
        return "FLOW".equals(connector) || "LOOKUP".equals(connector) || "REJECT".equals(connector);
    }

    private static XmlTag sourceSchema(XmlTag job, XmlTag connection) {
        String id = value(connection, "source");
        if (id == null) return null;
        for (XmlTag source : job.findSubTags("node")) {
            if (id.equals(parameterValue(source, "UNIQUE_NAME"))) return schema(source, connection, null);
        }
        return null;
    }

    private static XmlTag schema(XmlTag node, XmlTag connection, String tableName) {
        String metaname = connection == null ? null : value(connection, "metaname");
        String connector = connection == null ? "FLOW" : value(connection, "connectorName");
        XmlTag match = null;
        int matches = 0;
        for (XmlTag schema : node.findSubTags("metadata")) {
            String name = value(schema, "name");
            if ((metaname != null && metaname.equals(name)) || (tableName != null && tableName.equals(name))) return schema;
            if (Objects.equals(connector, value(schema, "connector"))) {
                match = schema;
                matches++;
            }
        }
        return matches == 1 ? match : null;
    }

    private static Map<String, String> columns(XmlTag schema) {
        Map<String, String> columns = new LinkedHashMap<>();
        if (schema != null) {
            for (XmlTag column : schema.findSubTags("column")) {
                String name = value(column, "name");
                if (identifier(name)) {
                    columns.putIfAbsent(name, javaType(value(column, "type"), !"false".equals(value(column, "nullable"))));
                }
            }
        }
        return columns;
    }

    static Map<String, String> context(XmlTag job) {
        Map<String, String> fields = new LinkedHashMap<>();
        String preferred = value(job, "defaultContext");
        XmlTag[] contexts = job.findSubTags("context");
        if (contexts.length == 0) return fields;
        XmlTag selected = contexts[0];
        for (XmlTag context : contexts) {
            if (preferred != null && preferred.equals(value(context, "name"))) {
                selected = context;
                break;
            }
        }
        for (XmlTag parameter : selected.findSubTags("contextParameter")) {
            String name = value(parameter, "name");
            if (identifier(name)) fields.putIfAbsent(name, javaType(value(parameter, "type"), true));
        }
        return fields;
    }

    static boolean identifier(String text) {
        return text != null && SourceVersion.isIdentifier(text) && !SourceVersion.isKeyword(text);
    }

    private static String javaType(String type, boolean nullable) {
        if (type == null) return "java.lang.Object";
        return switch (type) {
            case "id_String", "id_Password" -> "java.lang.String";
            case "id_Boolean" -> nullable ? "java.lang.Boolean" : "boolean";
            case "id_Byte" -> nullable ? "java.lang.Byte" : "byte";
            case "id_Character", "id_Char" -> nullable ? "java.lang.Character" : "char";
            case "id_Short" -> nullable ? "java.lang.Short" : "short";
            case "id_Integer" -> nullable ? "java.lang.Integer" : "int";
            case "id_Long" -> nullable ? "java.lang.Long" : "long";
            case "id_Float" -> nullable ? "java.lang.Float" : "float";
            case "id_Double" -> nullable ? "java.lang.Double" : "double";
            case "id_BigDecimal" -> "java.math.BigDecimal";
            case "id_BigInteger" -> "java.math.BigInteger";
            case "id_Date" -> "java.util.Date";
            case "id_byte[]" -> "byte[]";
            case "id_List" -> "java.util.List";
            case "id_Map" -> "java.util.Map";
            case "id_Document" -> "org.dom4j.Document";
            default -> "java.lang.Object";
        };
    }

    private TalendFragments() { }
}
