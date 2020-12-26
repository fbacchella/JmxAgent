package fr.jrds.jmxagent;

import java.beans.SimpleBeanInfo;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.rmi.server.RMIServerSocketFactory;
import java.security.NoSuchAlgorithmException;
import java.security.URIParameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import javax.rmi.ssl.SslRMIServerSocketFactory;

import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true, fluent = true)
public class Configuration {
    
    public static class BuilderBeanInfo extends SimpleBeanInfo {
    }

    public static class Builder {
        @Setter
        private String protocol = DEFAULTPROTOCOL.toString();
        @Setter
        private String port = null;
        @Setter
        private String hostname = null;
        // Mainly used for tests
        @Setter
        private SSLContext sslContext = null;
        @Setter
        private String withSsl = null;
        @Setter
        private String jaasName = null;
        @Setter
        private String jaasConfiguration = null;
        @Setter
        private String clientAuthentication = null;
        @Setter
        private String passwordFile = null;
        @Setter
        private String accessFile = null;
        @Setter
        private String strict = "true";
        private Builder() {
            try {
                hostname = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
            }
        }
        public Builder readSystemProperties() {
            Properties props = new Properties();
            for (String propName: systemProperties) {
                String propValue = System.getProperty(propName);
                if (propValue == null && booleanProperties.contains(propName)) {
                    propValue = "true";
                } else if (propValue == null) {
                    continue;
                }
                props.put(propName, propValue);
            }
            readProperties(props);
            return this;
        }
        public Builder configFile(String configFile) {
            Properties props = new Properties();

            try (Reader r = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)){
                props.load(r);
                readProperties(props);
            } catch (IOException e) {
                throw new IllegalArgumentException("Unusable configuration file: " + e.getMessage(), e);
            }
            return this;
        }
        Builder readProperties(Properties props) {
            for (String cfvar: new String[] {"com.sun.management.config.file", "configFile"}) {
                if (props.containsKey(cfvar)) {
                    Optional.ofNullable(props.get(cfvar))
                    .map(Object::toString)
                    .ifPresent(this::configFile);
                }
            }
            for (Map.Entry<Object, Object> e: props.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                switch(e.getKey().toString()) {
                case "java.rmi.server.useLocalHostname":
                    if ("true".equals(e.getValue().toString().toLowerCase(Locale.ENGLISH))) {
                        try {
                            hostname = InetAddress.getLocalHost().getCanonicalHostName();
                        } catch (UnknownHostException e1) {
                        }
                    }
                case "java.rmi.server.hostname":
                    hostname(e.getValue().toString());
                    break;
                case "com.sun.management.jmxremote.port":
                    port(e.getValue().toString());
                    break;
                case "com.sun.management.config.file":
                    // already handled
                    break;
                case "com.sun.management.jmxremote.registry.ssl":
                    withSsl(e.getValue().toString());
                    break;
                case "com.sun.management.jmxremote.ssl.enabled.protocols":
                    break;
                case "com.sun.management.jmxremote.ssl.need.client.auth":
                    clientAuthentication(e.getValue().toString());
                    break;
                case "com.sun.management.jmxremote.password.file":
                    passwordFile(e.getValue().toString());
                    break;
                case "com.sun.management.jmxremote.access.file":
                    accessFile(e.getValue().toString());
                    break;
                case "com.sun.management.jmxremote.login.config":
                    jaasName(e.getValue().toString());
                    break;
                case "com.sun.management.jmxremote.authenticate":
                    if ("false".equalsIgnoreCase(e.getValue().toString())) {
                        jaasName(null);
                        accessFile(null);
                        passwordFile(null);
                        strict(null);
                    }
                    break;
                default:
                    readBean(e.getKey().toString(), e.getValue().toString());
                }
            }
            return this;
        }
        Builder readBean(String name, String value) {
            try {
                Method m = Configuration.Builder.class.getMethod(name, String.class);
                if (m.getDeclaringClass() != Builder.class) {
                    throw new IllegalAccessException("Only native method can be used as bean");
                }
                if (value == null && booleanProperties.contains(name)) {
                    value = "true";
                }
                m.invoke(this, value);
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                throw new IllegalArgumentException("Invalid method '" + name + "'");
            }
            return this;
        }
        Configuration build() {
            return new Configuration(this);
        }
    }
    
    public static Builder getBuilder() {
        return new Builder();
    }

    static private final String[] systemProperties = new String[] {
            "java.rmi.server.hostname",
            "java.rmi.server.useLocalHostname",
            "com.sun.management.jmxremote.port",
            "com.sun.management.jmxremote.registry.ssl",
            "com.sun.management.jmxremote.ssl.enabled.protocols",
            "com.sun.management.jmxremote.ssl.enabled.cipher.suites",
            "com.sun.management.jmxremote.ssl.need.client.auth",
            "com.sun.management.jmxremote.authenticate",
            "com.sun.management.jmxremote.password.file",
            "com.sun.management.jmxremote.access.file",
            "com.sun.management.jmxremote.login.config",
            "com.sun.management.config.file",
    };

    static private String[] booleanPropertiesArray = new String[] {
            "java.rmi.server.useLocalHostname",
            "com.sun.management.jmxremote.ssl.need.client.auth",
            "com.sun.management.jmxremote.authenticate",
            "clientAuthentication",
            "strict",
            "withSsl"
    };
    static private final Set<String> booleanProperties = Arrays.stream(booleanPropertiesArray).collect(Collectors.toSet());

    static final public PROTOCOL DEFAULTPROTOCOL = PROTOCOL.rmi;
    public static enum PROTOCOL {
        rmi,
        jmxmp,
    }

    public final PROTOCOL protocol;
    public final int port;
    public final String hostname;
    public final boolean withSsl;
    public final String jaasName;
    public final String passwordFile;
    public final String accessFile;
    public final javax.security.auth.login.Configuration jaasConfig;
    public final boolean clientAuthentication;
    public final boolean strict;

    private final Map<ObjectName, Object> mbeans = new HashMap<>();

    private Configuration(Builder builder) {
        protocol = PROTOCOL.valueOf(builder.protocol.toLowerCase(Locale.ENGLISH));
        port = Integer.parseInt(builder.port);
        hostname = builder.hostname;
        withSsl = "true".equalsIgnoreCase(builder.withSsl);
        jaasName = builder.jaasName;
        clientAuthentication = "true".equalsIgnoreCase(builder.clientAuthentication);
        passwordFile = builder.passwordFile;
        accessFile = builder.accessFile;
        strict = "true".equalsIgnoreCase(builder.strict);
        if (builder.jaasConfiguration != null) {
            URIParameter cp = new URIParameter(Paths.get(builder.jaasConfiguration).toUri());
            try {
                jaasConfig = javax.security.auth.login.Configuration.getInstance("JavaLoginConfig", cp);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("JavaLoginConfig unavailable", e);
            }
        } else {
            jaasConfig = null;
        }
    }

    public Configuration register(ObjectName key, Object value) {
        mbeans.put(key, value);
        return this;
    }
    
    public RMIServerSocketFactory getSslSocketFactory() {
        return new SslRMIServerSocketFactory(null, null, null, clientAuthentication);
    }

}
