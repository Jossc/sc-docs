package com.vcg.docs.visitor;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.description.JavadocDescription;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedTypeVariable;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistClassDeclaration;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistEnumDeclaration;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistFieldDeclaration;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistInterfaceDeclaration;
import com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionInterfaceDeclaration;
import com.github.javaparser.utils.Pair;
import io.swagger.models.ArrayModel;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.RefModel;
import io.swagger.models.properties.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ResolveSwaggerType {

    private Map<String, Property> propertyMap = new ConcurrentHashMap<>();

    public Property resolve(Type type) {
        try {
            return resolve(type.resolve());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ObjectProperty();
    }

    private Property resolve(ResolvedType resolvedType) {
        String clazzName = resolvedType.describe();
        if (resolvedType instanceof ReferenceTypeImpl) {
            clazzName = ((ReferenceTypeImpl) resolvedType).getId();
        }

        Property property = resolveBaseType(clazzName);
        if (property != null) {
            return property;
        }


        if (resolvedType.isReferenceType()) {
            return resolveRefProperty(resolvedType.asReferenceType());
        }


        if (resolvedType.isArray()) {
            Property resolve = resolve(resolvedType.asArrayType().getComponentType());
            if (resolve instanceof ObjectProperty && resolve.getName() != null) {
                return new ArrayProperty(new RefProperty("#/definitions/" + resolve.getName()));
            }
            return new ArrayProperty(resolve);
        }


        return new ObjectProperty();
    }

    private Property resolveRefProperty(ResolvedReferenceType resolvedReferenceType) {
        ObjectProperty objectProperty = new ObjectProperty();
        Set<ResolvedFieldDeclaration> declaredFields = resolvedReferenceType.getDeclaredFields();
        for (ResolvedFieldDeclaration declaredField : declaredFields) {
            ResolvedType resolvedType = declaredField.getType();
            String name = declaredField.getName();
            if (!declaredField.isStatic() && declaredField instanceof JavaParserFieldDeclaration) {
                JavaParserFieldDeclaration field = (JavaParserFieldDeclaration) declaredField;
                FieldDeclaration wrappedNode = field.getWrappedNode();
                Property property = resolve(resolvedType);
                wrappedNode.getJavadocComment().ifPresent(c -> {
                    Javadoc javadoc = c.asJavadocComment().parse();
                    JavadocDescription description = javadoc.getDescription();
                    property.description(description.toText());
                });
                objectProperty.property(name, property);
                Property typeParameterProperty = resolveParameterProperty(property,resolvedReferenceType, resolvedType);
                if (typeParameterProperty != null) {
                    objectProperty.property(name, typeParameterProperty);
                }

            } else if (!declaredField.isStatic() && declaredField instanceof JavassistFieldDeclaration) {
                Property property = resolve(resolvedType);
                objectProperty.property(name, property);

                Property typeParameterProperty = resolveParameterProperty(property,resolvedReferenceType, resolvedType);
                if (typeParameterProperty != null) {
                    objectProperty.property(name, typeParameterProperty);
                }
            }




        }

//
//        if (declaredFields.size() == 0) {
//            List<ResolvedMethodDeclaration> allMethods = resolvedReferenceType.getAllMethods();
//            for (ResolvedMethodDeclaration method : allMethods) {
//                String getMethodName = method.getName();
//                if (!getMethodName.equals("getClass") && getMethodName.startsWith("get")
//                        && method.getTypeParameters().size() == 0) {
//                    String name = getMethodName.substring(3);
//                    if (name.length() > 1) {
//                        ResolvedType returnType = method.getReturnType();
//                        Property resolve = resolve(returnType);
//                        objectProperty.property(name.substring(0, 1).toLowerCase() + name.substring(1), resolve);
//                    }
//                }
//            }
//        }

        ResolvedReferenceTypeDeclaration typeDeclaration = resolvedReferenceType.getTypeDeclaration();
        if (typeDeclaration instanceof JavaParserClassDeclaration) {
            JavaParserClassDeclaration javaParserClassDeclaration = (JavaParserClassDeclaration) typeDeclaration;
            String name = javaParserClassDeclaration.getName();
            objectProperty.name(name);
            ClassOrInterfaceDeclaration wrappedNode = javaParserClassDeclaration.getWrappedNode();
            wrappedNode.getJavadocComment().ifPresent(c -> objectProperty.description(c.parse().toText()));
        }


        if (typeDeclaration instanceof JavassistClassDeclaration) {
            JavassistClassDeclaration javaParserClassDeclaration = (JavassistClassDeclaration) typeDeclaration;
            String name = javaParserClassDeclaration.getName();
            objectProperty.name(name);
        }


        if (typeDeclaration instanceof ReflectionInterfaceDeclaration) {
            List<Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParametersMap = resolvedReferenceType.getTypeParametersMap();
            try {
                Class<?> aClass = Class.forName(typeDeclaration.getId());
                if (Set.class.isAssignableFrom(aClass)) {
                    Property value = resolve(typeParametersMap.get(0).b);
                    if (value instanceof ObjectProperty && value.getName() != null) {
                        return new ArrayProperty(new RefProperty("#/definitions/" + value.getName()));
                    }
                    return new ArrayProperty(value).uniqueItems();
                } else if (Collection.class.isAssignableFrom(aClass)) {
                    Property value = resolve(typeParametersMap.get(0).b);
                    if (value instanceof ObjectProperty && value.getName() != null) {
                        return new ArrayProperty(new RefProperty("#/definitions/" + value.getName()));
                    }
                    return new ArrayProperty(value);
                } else if (Map.class.isAssignableFrom(aClass)) {
                    Property value = resolve(typeParametersMap.get(1).b);
                    if (value instanceof ObjectProperty && value.getName() != null) {
                        return new MapProperty().additionalProperties(new RefProperty("#/definitions/" + value.getName()));
                    }
                    return new MapProperty().additionalProperties(value);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (typeDeclaration instanceof JavassistInterfaceDeclaration) {
            JavassistInterfaceDeclaration javassistInterfaceDeclaration = (JavassistInterfaceDeclaration) typeDeclaration;
            String name = javassistInterfaceDeclaration.getName();
            objectProperty.name(name);
        }


        if (typeDeclaration instanceof JavaParserEnumDeclaration) {
            JavaParserEnumDeclaration javaParserEnumDeclaration = (JavaParserEnumDeclaration) typeDeclaration;
            String name = javaParserEnumDeclaration.getName();
            EnumDeclaration wrappedNode = javaParserEnumDeclaration.getWrappedNode();
            List<String> enums = new ArrayList<>();
            StringBuffer sb = new StringBuffer();
            for (EnumConstantDeclaration entry : wrappedNode.getEntries()) {
                String fieldName = entry.getName().asString();
                entry.getJavadocComment().ifPresent(c -> sb.append(fieldName)
                        .append(":")
                        .append(c.parse().getDescription().toText())
                        .append("\t")
                );
                enums.add(fieldName);
            }
            Property enumProperty = new StringProperty()._enum(enums).description(sb.toString());
            propertyMap.put(name, enumProperty);
            return enumProperty;

        }


        if (typeDeclaration instanceof JavassistEnumDeclaration) {
            JavassistEnumDeclaration javaParserEnumDeclaration = (JavassistEnumDeclaration) typeDeclaration;
            String name = javaParserEnumDeclaration.getName();
            List<String> enums = new ArrayList<>();
            for (ResolvedEnumConstantDeclaration enumConstant : javaParserEnumDeclaration.getEnumConstants()) {
                enums.add(enumConstant.getName());
            }
            Property enumProperty = new StringProperty()._enum(enums);
            propertyMap.put(name, enumProperty);
            return enumProperty;

        }

        Map<String, ResolvedType> resolvedTypeMap = resolveTypeParameter(resolvedReferenceType);
        if (objectProperty.getName() != null) {
            List<String> typeNames = new ArrayList<>();
            for (Map.Entry<String, ResolvedType> entry : resolvedTypeMap.entrySet()) {
                Property resolve = resolve(entry.getValue());
                if (resolve.getName() != null) {
                    typeNames.add(resolve.getName());
                }
            }
            if (!typeNames.isEmpty()) {
                objectProperty.name(objectProperty.getName() + "«" + String.join(",", typeNames) + "»");
            }

            propertyMap.put(objectProperty.getName(), objectProperty);
        }

        return objectProperty;
    }


    public Map<String, Model> getModelMap() {
        Map<String, Model> modelMap = new LinkedHashMap<>();
        for (Map.Entry<String, Property> entry : propertyMap.entrySet()) {
            Property value = entry.getValue();
            Model model = toModel(value);
            if (model != null) {
                modelMap.put(entry.getKey(), model);
            }
        }
        return modelMap;
    }

    private Map<String, ResolvedType> resolveTypeParameter(ResolvedType resolvedType) {
        Map<String, ResolvedType> map = new HashMap<>();
        if (resolvedType.isReferenceType()) {
            ResolvedReferenceType resolvedReferenceType = resolvedType.asReferenceType();
            List<Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParametersMap = resolvedReferenceType.getTypeParametersMap();
            for (Pair<ResolvedTypeParameterDeclaration, ResolvedType> entry : typeParametersMap) {
                String aName = entry.a.getName();
                Optional<ResolvedType> genericParameter = resolvedReferenceType.getGenericParameterByName(aName);
                genericParameter.ifPresent(c -> {
                    if (!"java.lang.Object".equals(c.describe())) {
                        if (c.isTypeVariable()) {
                            ResolvedTypeVariable resolvedTypeVariable = c.asTypeVariable();
                            map.put(resolvedTypeVariable.describe(), c);
                        } else {
                            map.put(aName, c);
                        }
                    }
                });
            }
        }
        return map;
    }

    private Property resolveParameterProperty(Property property, ResolvedReferenceType resolvedReferenceType, ResolvedType resolvedType) {

        if (resolvedType.isTypeVariable()) {
            ResolvedTypeVariable resolvedTypeVariable = resolvedType.asTypeVariable();
            Optional<ResolvedType> genericParameter = resolvedReferenceType.getGenericParameterByName(resolvedTypeVariable.describe());
            if (genericParameter.isPresent()) {
                return resolve(genericParameter.get());
            }
        }


        if (resolvedType.isReferenceType()) {
            ResolvedReferenceType fieldReferenceType = resolvedType.asReferenceType();
            List<Pair<ResolvedTypeParameterDeclaration, ResolvedType>> typeParametersMap = fieldReferenceType.getTypeParametersMap();
            if (typeParametersMap.size() > 0) {
                for (Pair<ResolvedTypeParameterDeclaration, ResolvedType> typePair : typeParametersMap) {
                    String clazzName = typePair.a.getName();
                    ResolvedType b = typePair.b;
                    if (b.isTypeVariable()) {
                        Optional<ResolvedType> genericResolvedTypeOptional = resolvedType.asReferenceType().getGenericParameterByName(clazzName);
                        if (genericResolvedTypeOptional.isPresent()) {
                            ResolvedType genericResolvedType = genericResolvedTypeOptional.get();
                            Optional<ResolvedType> genericParameter = resolvedReferenceType.getGenericParameterByName(genericResolvedType.describe());
                            if (genericParameter.isPresent()) {
                                ResolvedType genericResolvedParameter = genericParameter.get();
                                Property resolve = resolve(genericResolvedParameter);
                                if (property instanceof ArrayProperty) {
                                    return ((ArrayProperty) property).items(resolve);
                                }

                                if (property instanceof MapProperty) {
                                    return ((MapProperty) property).additionalProperties(resolve);
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    public Model convertToModel(Property property) {
        if (property != null && property.getName() != null) {
            return new RefModel("#/definitions/" + property.getName());
        }
        return toModel(property);
    }


    private Model toModel(Property property) {

        if (property instanceof ObjectProperty && ((ObjectProperty) property).getProperties() != null) {
            ModelImpl model = new ModelImpl();
            model.setDescription(property.getDescription());
            Map<String, Property> properties = ((ObjectProperty) property).getProperties();
            for (Map.Entry<String, Property> entry : properties.entrySet()) {
                Property value = entry.getValue();
                if (value instanceof ObjectProperty) {
                    if (value.getName() == null) {
                        model.property(entry.getKey(), new ObjectProperty());
                    } else {
                        model.property(entry.getKey(), new RefProperty("#/definitions/" + value.getName()));
                    }
                } else if (value instanceof ArrayProperty || value instanceof MapProperty) {
                    toModel(value);
                    model.property(entry.getKey(), value);
                } else {
                    model.property(entry.getKey(), entry.getValue());
                }
            }
            return model;
        }
        if (property instanceof ArrayProperty) {
            ArrayProperty arrayProperty = (ArrayProperty) property;
            Property items = arrayProperty.getItems();

            if (items instanceof ArrayProperty || items instanceof MapProperty) {
                toModel(items);
            } else if (items instanceof ObjectProperty) {
                if (items.getName() == null) {
                    arrayProperty.items(new ObjectProperty());
                } else {
                    arrayProperty.items(new RefProperty("#/definitions/" + items.getName()));
                }
            }
            return new ArrayModel().items(items != null ? items : new ObjectProperty());
        }

        if (property instanceof MapProperty) {
            MapProperty mapProperty = (MapProperty) property;
            Property additionalProperties = mapProperty.getAdditionalProperties();
            if (additionalProperties instanceof ObjectProperty) {
                ObjectProperty objectProperty = (ObjectProperty) additionalProperties;
                if (objectProperty.getName() == null) {
                    mapProperty.setAdditionalProperties(new ObjectProperty());
                } else {
                    mapProperty.setAdditionalProperties(new RefProperty("#/definitions/" + objectProperty.getName()));
                }
            } else {
                toModel(additionalProperties);
            }
            return new ModelImpl().additionalProperties(mapProperty);
        }

        if (property instanceof StringProperty) {
            StringProperty stringProperty = (StringProperty) property;
            if (stringProperty.getEnum() != null && stringProperty.getEnum().size() > 0) {
                return new ModelImpl()._enum(stringProperty.getEnum());
            }
        }

        return null;
    }


    private Property resolveBaseType(String clazzName) {
        if ("int".equals(clazzName)
                || "java.lang.Integer".equals(clazzName)
                || "java.math.BigInteger".equals(clazzName)
                || "java.lang.Short".equals(clazzName)
                || "short".equals(clazzName)
        ) {
            return new IntegerProperty();
        }

        if ("long".equals(clazzName) || "java.lang.Long".equals(clazzName)) {
            return new LongProperty();
        }

        if ("double".equals(clazzName) || "java.lang.Double".equals(clazzName) || "java.math.BigDecimal".equals(clazzName)) {
            return new DoubleProperty();
        }

        if ("float".equals(clazzName) || "java.lang.Float".equals(clazzName)) {
            return new FloatProperty();
        }

        if ("boolean".equals(clazzName) || "java.lang.Boolean".equals(clazzName)) {
            return new BooleanProperty();
        }

        if ("byte".equals(clazzName) || "java.lang.Byte".equals(clazzName)) {
            return new StringProperty("byte");
        }

        if ("byte[]".equals(clazzName) || "java.lang.Byte[]".equals(clazzName)) {
            return new ByteArrayProperty();
        }

        if ("java.lang.String".equals(clazzName) || "java.lang.CharSequence".equals(clazzName)) {
            return new StringProperty();
        }

        if ("java.util.Date".equals(clazzName)) {
            return new StringProperty("data-time").example("2018-09-10T13:11:43.123");
        }

        if ("org.springframework.web.multipart.MultipartFile".equals(clazzName)) {
            return new FileProperty();
        }

        if ("java.util.UUID".equals(clazzName)) {
            return new UUIDProperty();
        }

        if ("com.alibaba.fastjson.JSONObject".equals(clazzName)
                || "com.google.gson.JsonObject".equals(clazzName)
                || "com.fasterxml.jackson.databind.node.ObjectNode".equals(clazzName)
        ) {
            return new MapProperty().additionalProperties(new ObjectProperty());
        }

        if ("com.alibaba.fastjson.JSONArray".equals(clazzName)
                || "com.google.gson.JsonArray".equals(clazzName)
                || "com.fasterxml.jackson.databind.node.ArrayNode".equals(clazzName)
        ) {
            return new ArrayProperty().items(new ObjectProperty());
        }

        return null;
    }

}
