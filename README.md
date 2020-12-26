# Simple JMX starter

This Javaagent aims to make starting a JMX server simpler and more secure. It's not
easy to have a rather secure and firewall-friendy remote JMX managent service started. This
agent provide two missing features, a single-port access and a default tighten access, without any
write access or remote action.

Common use case is :

    java -java -javaagent:.../JmxAgent-0.0.1-SNAPSHOT.jar=port=<port>
    
It handles the following properties:

 * protocol, default to `rmi`, can also be `jmxmp`
 * port, the listening port, no default
 * hostname, same effect than `java.rmi.server.hostnamejava.rmi.server.hostname` ; default to the IP resolution of the local hostname.
 * sslContext, define the SSL context to use
 * withSsl, same effect than `com.sun.management.jmxremote.registry.ssl`
 * jaasName, same effect than `com.sun.management.jmxremote.login.config`, define the JAAS entry name to use.
 * jaasConfiguration, define the JAAS property file to use.
 * clientAuthentication, same effect than `com.sun.management.jmxremote.ssl.need.client.auth`
 * passwordFile, same effect than `com.sun.management.jmxremote.password.file`
 * accessFile, same effect than `com.sun.management.jmxremote.access.file`
 * strict, ensure restricted access, default to `true`
 * configFile, a properties file.

The priority of security settings is:
 1. If both `jaasName` and `jaasConfiguration` are used, it defines a custom JAAS configuration.
 2. If only `jaasName` is defined, it used a default JAAS configuration and choose the given JAAS entry.
 3. If `passwordFile` is defined, it use the internal JMX automatically generated configuration.
 3. If `strict` is set to true (the default), only read only access is allowed, many features from JMC will be disabled.
 4. No restrictions, full JMX access is provided, same effect than `com.sun.management.jmxremote.authenticate=false`

The property `configFile` define a java standard properties files than can define all given properties, and also uses the standard system properties:

 * java.rmi.server.hostname
 * java.rmi.server.useLocalHostname
 * com.sun.management.jmxremote.port
 * com.sun.management.jmxremote.registry.ssl
 * com.sun.management.jmxremote.ssl.enabled.protocols
 * com.sun.management.jmxremote.ssl.enabled.cipher.suites
 * com.sun.management.jmxremote.ssl.need.client.auth
 * com.sun.management.jmxremote.authenticate
 * com.sun.management.jmxremote.password.file
 * com.sun.management.jmxremote.access.file
 * com.sun.management.jmxremote.login.config
 * com.sun.management.config.file

Those property can also be defined as system properties and will be used.

At startup, system properties are resolved, and if present, `com.sun.management.config.file` will be used.
Then each argument given as a java argument is resolved and any given `configFile` parameter is immediately processed.
If multiple definition of a property is given, using a system property, a properties file or a explicit value, the last one will be used.

It sets the following properties:

 * java.rmi.server.hostname
 * java.rmi.server.useLocalHostname
