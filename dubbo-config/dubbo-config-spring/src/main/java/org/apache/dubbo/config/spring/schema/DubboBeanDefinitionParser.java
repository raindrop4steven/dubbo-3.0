/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.AbstractServiceConfig;
import org.apache.dubbo.config.ArgumentConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.Constants;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.reference.ReferenceAttributes;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.core.env.Environment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERSION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private static final String ONRETURN = "onreturn";
    private static final String ONTHROW = "onthrow";
    private static final String ONINVOKE = "oninvoke";
    private static final String METHOD = "Method";
    private static final String BEAN_NAME = "BEAN_NAME";
    private final Class<?> beanClass;
    private static Map<String, Map<String, Class>> beanPropsCache = new HashMap<>();

    /**
     *
     * @param beanClass 当前标签解析出的内容要封装的类
     */
    public DubboBeanDefinitionParser(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    /**
     *
     * @param element 当前要解析的标签
     * @param parserContext 解析上下文，其中包含了当前配置文件中所有其它标签的解析信息
     * @param beanClass 当前标签解析出的内容要封装的类，其中就是存放的从标签中读出的属性。读到了什么就记录下什么
     * @param registered 解析出的标签是否需要注解到注册中心
     * @return 解析对象。将读取出的数据最终要构建出一个“逻辑”上的对象。例如，构建出一个“注册中心”对象、“协议”对象等
     */
    @SuppressWarnings("unchecked")
    private static RootBeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean registered) {
        // ----------------------- 1 创建并初始化解析对象 ---------------
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        // 指定该bean不进行延迟初始化
        beanDefinition.setLazyInit(false);


        // ----------------------- 2 解决id问题: 为空与重复问题 ---------------
        // config id
        // 获取当前解析标签的id属性
        String configId = resolveAttribute(element, "id", parserContext);
        // 若id不空，则写入到beanDefinition
        if (StringUtils.isNotEmpty(configId)) {
            beanDefinition.getPropertyValues().addPropertyValue("id", configId);
        }

        // get id from name
        // 若id属性为空，则获取name属性赋值给id
        if (StringUtils.isEmpty(configId)) {
            configId = resolveAttribute(element, "name", parserContext);
        }
        // 此时若id不空，则处理{}点位符
        if (StringUtils.isNotEmpty(configId)) {
            configId = resolvePlaceholders(configId, parserContext);
        }

        // bean名称取id属性值
        String beanName = configId;
        // 若此时beanName为空，即id属性仍为空
        if (StringUtils.isEmpty(beanName)) {
            // generate bean name
            // 取beanClass的类名
            String prefix = beanClass.getName();
            int counter = 0;
            // beanName为类名与数字的拼接
            beanName = prefix + "#" + counter;
            // 查看该beanName是否重复。若重复，则让数字增一
            while (parserContext.getRegistry().containsBeanDefinition(beanName)) {
                beanName = prefix + "#" + (counter++);
            }
        }
        // 此时beanName一定不空，且不重复
        beanDefinition.setAttribute(BEAN_NAME, beanName);

        // ----------------------- 3 对特殊标签的处理 ---------------
        // 对<dubbo:protocol/>标签的处理
        if (ProtocolConfig.class.equals(beanClass)) {
//            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
//                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
//                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
//                if (property != null) {
//                    Object value = property.getValue();
//                    if (value instanceof ProtocolConfig && beanName.equals(((ProtocolConfig) value).getName())) {
//                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(beanName));
//                    }
//                }
//            }

        // 对<dubbo:service/>标签的处理
        } else if (ServiceBean.class.equals(beanClass)) {
            String className = resolveAttribute(element, "class", parserContext);
            if (StringUtils.isNotEmpty(className)) {
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                parseProperties(element.getChildNodes(), classDefinition, parserContext);
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, beanName + "Impl"));
            }
        }


        // ----------------------- 4 对普通标签的普适性处理 ---------------
        Map<String, Class> beanPropTypeMap = beanPropsCache.get(beanClass.getName());
        if (beanPropTypeMap == null) {
            beanPropTypeMap = new HashMap<>();
            beanPropsCache.put(beanClass.getName(), beanPropTypeMap);
            if (ReferenceBean.class.equals(beanClass)) {
                //extract bean props from ReferenceConfig
                getPropertyMap(ReferenceConfig.class, beanPropTypeMap);
            } else {
                getPropertyMap(beanClass, beanPropTypeMap);
            }
        }

        ManagedMap parameters = null;
        Set<String> processedProps = new HashSet<>();
        for (Map.Entry<String, Class> entry : beanPropTypeMap.entrySet()) {
            String beanProperty = entry.getKey();
            Class type = entry.getValue();
            String property = StringUtils.camelToSplitName(beanProperty, "-");
            processedProps.add(property);
            if ("parameters".equals(property)) {
                parameters = parseParameters(element.getChildNodes(), beanDefinition, parserContext);
            } else if ("methods".equals(property)) {
                parseMethods(beanName, element.getChildNodes(), beanDefinition, parserContext);
            } else if ("arguments".equals(property)) {
                parseArguments(beanName, element.getChildNodes(), beanDefinition, parserContext);
            } else {
                String value = resolveAttribute(element, property, parserContext);
                if (value != null) {
                    value = value.trim();
                    if (value.length() > 0) {
                        if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                            RegistryConfig registryConfig = new RegistryConfig();
                            registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                            beanDefinition.getPropertyValues().addPropertyValue(beanProperty, registryConfig);
                        } else if ("provider".equals(property) || "registry".equals(property) || ("protocol".equals(property) && AbstractServiceConfig.class.isAssignableFrom(beanClass))) {
                            /**
                             * For 'provider' 'protocol' 'registry', keep literal value (should be id/name) and set the value to 'registryIds' 'providerIds' protocolIds'
                             * The following process should make sure each id refers to the corresponding instance, here's how to find the instance for different use cases:
                             * 1. Spring, check existing bean by id, see{@link ServiceBean#afterPropertiesSet()}; then try to use id to find configs defined in remote Config Center
                             * 2. API, directly use id to find configs defined in remote Config Center; if all config instances are defined locally, please use {@link ServiceConfig#setRegistries(List)}
                             */
                            beanDefinition.getPropertyValues().addPropertyValue(beanProperty + "Ids", value);
                        } else {
                            Object reference;
                            if (isPrimitive(type)) {
                                value = getCompatibleDefaultValue(property, value);
                                reference = value;
                            } else if (ONRETURN.equals(property) || ONTHROW.equals(property) || ONINVOKE.equals(property)) {
                                int index = value.lastIndexOf(".");
                                String ref = value.substring(0, index);
                                String method = value.substring(index + 1);
                                reference = new RuntimeBeanReference(ref);
                                beanDefinition.getPropertyValues().addPropertyValue(property + METHOD, method);
                            } else {
                                if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                    BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                    if (!refBean.isSingleton()) {
                                        throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                    }
                                }
                                reference = new RuntimeBeanReference(value);
                            }
                            if (reference != null) {
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, reference);
                            }
                        }
                    }
                }
            }
        }

        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!processedProps.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }

        // post-process after parse attributes
        if (ProviderConfig.class.equals(beanClass)) {
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", beanName, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            parseNested(element, parserContext, ReferenceBean.class, true, "reference", "consumer", beanName, beanDefinition);
        } else if (ReferenceBean.class.equals(beanClass)) {
            configReferenceBean(element, parserContext, beanDefinition, null);
        }

        // register bean definition
        if (parserContext.getRegistry().containsBeanDefinition(beanName)) {
            throw new IllegalStateException("Duplicate spring bean name: " + beanName);
        }

        if (registered) {
            parserContext.getRegistry().registerBeanDefinition(beanName, beanDefinition);
        }
        return beanDefinition;
    }

    private static void configReferenceBean(Element element, ParserContext parserContext, RootBeanDefinition beanDefinition, BeanDefinition consumerDefinition) {
        // process interface class
        String interfaceName = resolveAttribute(element, ReferenceAttributes.INTERFACE, parserContext);
        String generic = resolveAttribute(element, ReferenceAttributes.GENERIC, parserContext);
        if (StringUtils.isBlank(generic) && consumerDefinition != null) {
            // get generic from consumerConfig
            generic = (String) consumerDefinition.getPropertyValues().get(ReferenceAttributes.GENERIC);
        }
        if (generic != null) {
            Environment environment = parserContext.getReaderContext().getEnvironment();
            generic = environment.resolvePlaceholders(generic);
            beanDefinition.getPropertyValues().add(ReferenceAttributes.GENERIC, generic);
        }
        beanDefinition.setAttribute(ReferenceAttributes.INTERFACE_NAME, interfaceName);

        Class interfaceClass = ReferenceConfig.determineInterfaceClass(generic, interfaceName);
        beanDefinition.setAttribute(ReferenceAttributes.INTERFACE_CLASS, interfaceClass);

        // TODO Only register one reference bean for same (group, interface, version)

        // create decorated definition for reference bean, Avoid being instantiated when getting the beanType of ReferenceBean
        // see org.springframework.beans.factory.support.AbstractBeanFactory#getTypeForFactoryBean()
        GenericBeanDefinition targetDefinition = new GenericBeanDefinition();
        targetDefinition.setBeanClass(interfaceClass);
        String beanName = (String) beanDefinition.getAttribute(BEAN_NAME);
        beanDefinition.setDecoratedDefinition(new BeanDefinitionHolder(targetDefinition, beanName+"_decorated"));

        // signal object type since Spring 5.2
        beanDefinition.setAttribute(Constants.OBJECT_TYPE_ATTRIBUTE, interfaceClass);

        //mark property value as optional
        List<PropertyValue> propertyValues = beanDefinition.getPropertyValues().getPropertyValueList();
        for (PropertyValue propertyValue : propertyValues) {
            propertyValue.setOptional(true);
        }
    }

    private static void getPropertyMap(Class<?> beanClass, Map<String, Class> beanPropsMap) {
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {
                Class<?> type = setter.getParameterTypes()[0];
                String beanProperty = name.substring(3, 4).toLowerCase() + name.substring(4);
                // check the setter/getter whether match
                Method getter = null;
                try {
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                        // ignore, there is no need any log here since some class implement the interface: EnvironmentAware,
                        // ApplicationAware, etc. They only have setter method, otherwise will cause the error log during application start up.
                    }
                }
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }
                beanPropsMap.put(beanProperty, type);
            }
        }
    }

    private static String getCompatibleDefaultValue(String property, String value) {
        if ("async".equals(property) && "false".equals(value)
                || "timeout".equals(property) && "0".equals(value)
                || "delay".equals(property) && "0".equals(value)
                || "version".equals(property) && "0.0.0".equals(value)
                || "stat".equals(property) && "-1".equals(value)
                || "reliable".equals(property) && "false".equals(value)) {
            // backward compatibility for the default value in old version's xsd
            value = null;
        }
        return value;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean registered, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList == null) {
            return;
        }
        boolean first = true;
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            if (tag.equals(node.getNodeName())
                    || tag.equals(node.getLocalName())) {
                if (first) {
                    first = false;
                    String isDefault = resolveAttribute(element, "default", parserContext);
                    if (StringUtils.isEmpty(isDefault)) {
                        beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                    }
                }
                RootBeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, registered);
                if (subDefinition != null) {
                    if (StringUtils.isNotEmpty(ref)) {
                        subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                    }
                    if (ReferenceBean.class.equals(beanClass)) {
                        configReferenceBean((Element) node, parserContext, subDefinition, beanDefinition);
                    }
                }
            }
        }
    }

    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("property".equals(element.getNodeName())
                    || "property".equals(element.getLocalName())) {
                String name = resolveAttribute(element, "name", parserContext);
                if (StringUtils.isNotEmpty(name)) {
                    String value = resolveAttribute(element, "value", parserContext);
                    String ref = resolveAttribute(element, "ref", parserContext);
                    if (StringUtils.isNotEmpty(value)) {
                        beanDefinition.getPropertyValues().addPropertyValue(name, value);
                    } else if (StringUtils.isNotEmpty(ref)) {
                        beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                    } else {
                        throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return null;
        }
        ManagedMap parameters = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("parameter".equals(element.getNodeName())
                    || "parameter".equals(element.getLocalName())) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String key = resolveAttribute(element, "key", parserContext);
                String value = resolveAttribute(element, "value", parserContext);
                boolean hide = "true".equals(resolveAttribute(element, "hide", parserContext));
                if (hide) {
                    key = HIDE_KEY_PREFIX + key;
                }
                parameters.put(key, new TypedStringValue(value, String.class));
            }
        }
        return parameters;
    }

    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        ManagedList methods = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("method".equals(element.getNodeName()) || "method".equals(element.getLocalName())) {
                String methodName = resolveAttribute(element, "name", parserContext);
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute == null");
                }
                if (methods == null) {
                    methods = new ManagedList();
                }
                RootBeanDefinition methodBeanDefinition = parse(element,
                        parserContext, MethodConfig.class, false);
                String beanName = id + "." + methodName;

                // If the PropertyValue named "id" can't be found,
                // bean name will be taken as the "id" PropertyValue for MethodConfig
                if (!hasPropertyValue(methodBeanDefinition, "id")) {
                    addPropertyValue(methodBeanDefinition, "id", beanName);
                }

                BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                        methodBeanDefinition, beanName);
                methods.add(methodBeanDefinitionHolder);
            }
        }
        if (methods != null) {
            beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
        }
    }

    private static boolean hasPropertyValue(AbstractBeanDefinition beanDefinition, String propertyName) {
        return beanDefinition.getPropertyValues().contains(propertyName);
    }

    private static void addPropertyValue(AbstractBeanDefinition beanDefinition, String propertyName, String propertyValue) {
        if (StringUtils.isBlank(propertyName) || StringUtils.isBlank(propertyValue)) {
            return;
        }
        beanDefinition.getPropertyValues().addPropertyValue(propertyName, propertyValue);
    }

    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        ManagedList arguments = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("argument".equals(element.getNodeName()) || "argument".equals(element.getLocalName())) {
                String argumentIndex = resolveAttribute(element, "index", parserContext);
                if (arguments == null) {
                    arguments = new ManagedList();
                }
                BeanDefinition argumentBeanDefinition = parse(element,
                        parserContext, ArgumentConfig.class, false);
                String name = id + "." + argumentIndex;
                BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                        argumentBeanDefinition, name);
                arguments.add(argumentBeanDefinitionHolder);
            }
        }
        if (arguments != null) {
            beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, true);
    }

    private static String resolveAttribute(Element element, String attributeName, ParserContext parserContext) {
        String attributeValue = element.getAttribute(attributeName);
        // Early resolve place holder may be wrong ( Before PropertySourcesPlaceholderConfigurer/PropertyPlaceholderConfigurer )
        //https://github.com/apache/dubbo/pull/6079
        //https://github.com/apache/dubbo/issues/6035
//        Environment environment = parserContext.getReaderContext().getEnvironment();
//        return environment.resolvePlaceholders(attributeValue);
        return attributeValue;
    }

    private static String resolvePlaceholders(String str, ParserContext parserContext) {
        return parserContext.getReaderContext().getEnvironment().resolveRequiredPlaceholders(str);
    }
}
