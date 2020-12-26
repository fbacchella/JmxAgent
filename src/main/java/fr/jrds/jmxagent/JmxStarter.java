package fr.jrds.jmxagent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXAuthenticator;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXPrincipal;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import fr.jrds.jmxagent.Configuration.PROTOCOL;

public class JmxStarter {

    private static Optional<JmxStarter> instance;

    public static void premain(String agentArgs) {
        try {
            Configuration.Builder builder = Configuration.getBuilder().readSystemProperties();
            if (agentArgs != null && ! agentArgs.isEmpty()) {
                String[] args  = agentArgs.split(File.pathSeparator);
                for (String arg: args) {
                    if(arg.isEmpty()) {
                        continue;
                    }
                    String[] argDetails = arg.split("=");
                    if (argDetails.length == 2) {
                        builder.readBean(argDetails[0], argDetails[1]);
                    } else {
                        builder.readBean(argDetails[0], "true");
                    }
                }
            }
            Configuration conf = builder.build();
            instance = Optional.ofNullable(new JmxStarter(conf)) ;
        } catch (IOException | SecurityException | IllegalArgumentException | MBeanRegistrationException | InstanceNotFoundException | MalformedObjectNameException e) {
            instance = Optional.empty();
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    public static void stop() {
        instance.ifPresent(JmxStarter::stopServer);
    }

    private final Configuration props;
    private final JMXConnectorServer cs;

    JmxStarter(Configuration props) throws IOException, MBeanRegistrationException, InstanceNotFoundException, MalformedObjectNameException {
        this.props = props;
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        Map<String, Object> env = new HashMap<>();
        RMIClientSocketFactory csf = null;
        RMIServerSocketFactory ssf = null;
        if (props.hostname != null) {
            // No other way to set that, resolution is hidded deeply in sun.rmi.transport.tcp.TCPEndpoint, in a static attribute
            System.setProperty("java.rmi.server.hostname", props.hostname);
            System.setProperty("java.rmi.server.useLocalHostname", "false");
        }
        if (props.withSsl) {
            env.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, props.getSslSocketFactory());
        }
        boolean withAuthentication = false;
        if (props.jaasConfig != null && props.jaasName != null) {
            JMXAuthenticator authenticator = this::authenticate;
            env.put(JMXConnectorServer.AUTHENTICATOR, authenticator);
            withAuthentication = true;
        } else if (props.jaasName != null) {
            env.put("jmx.remote.x.login.config", props.jaasName);
            withAuthentication = true;
        } else if (props.passwordFile != null) {
            env.put("jmx.remote.x.password.file", props.passwordFile);
            withAuthentication = true;
        }
        Optional<Path> tempFile = Optional.empty();
        if (withAuthentication && props.accessFile != null) {
            env.put("jmx.remote.x.access.file", props.accessFile);
        } else if (!withAuthentication && props.strict) {
            for (String on: new String[] {"com.sun.management:type=DiagnosticCommand",
                                          "com.sun.management:type=HotSpotDiagnostic",
                                          "jdk.management.jfr:type=FlightRecorder"}) {
                try {
                    mbs.unregisterMBean(new ObjectName(on));
                } catch (InstanceNotFoundException | MBeanRegistrationException ex) {
                }
            }
            tempFile = Optional.of(Files.createTempFile(null, null));
            try (InputStream access = JmxStarter.class.getClassLoader().getResourceAsStream("jmxremote.access")) {
                Files.copy(access, tempFile.get(), StandardCopyOption.REPLACE_EXISTING);
            }
            env.put("jmx.remote.x.access.file", tempFile.get().toAbsolutePath().toString());
            JMXAuthenticator authenticator = this::anonymous;
            env.put(JMXConnectorServer.AUTHENTICATOR, authenticator);
        }
        try {
            String path = "/";
            if (props.protocol == PROTOCOL.rmi) {
                java.rmi.registry.LocateRegistry.createRegistry(props.port, csf, ssf);
                path = String.format("/jndi/rmi://0.0.0.0:%s/jmxrmi", props.port);
            }
            JMXServiceURL url = new JMXServiceURL(props.protocol.toString(), "0.0.0.0", props.port, path);
            cs = JMXConnectorServerFactory.newJMXConnectorServer(url, env, mbs);
            cs.start();
        } finally {
            tempFile.ifPresent(t -> {
                try {
                    Files.deleteIfExists(t);
                } catch (IOException e) {
                }
            });
        }
    }
    
    private Subject authenticate(Object credentials) {
        Subject s = null;
        if ((credentials instanceof String[])) {
            String[] loginPassword = (String[]) credentials;
            if (loginPassword.length == 2) {
                try {
                    s = checkJaas(loginPassword[0],
                                  loginPassword[1].toCharArray());
                } catch (LoginException e) {
                    throw new SecurityException("Failed user authentication", e);
                }
                loginPassword[1] = null;
                if (s == null) {
                    throw new SecurityException("Invalid user");
                }
            }
        } else {
            throw new SecurityException("No valid credentials");
        }
        return s;
    }

    private Subject anonymous(Object credentials) {
        Principal p = new JMXPrincipal("anonymous");
        return new Subject(true, Collections.singleton(p), Collections.emptySet(), Collections.emptySet());
    }

    private Subject checkJaas(String tryLogin, char[] tryPassword) throws LoginException {
        CallbackHandler cbHandler = callbacks -> {
            for (Callback cb: callbacks) {
                if (cb instanceof NameCallback) {
                    NameCallback nc = (NameCallback)cb;
                    nc.setName(tryLogin);
                } else if (cb instanceof PasswordCallback) {
                    PasswordCallback pc = (PasswordCallback)cb;
                    pc.setPassword(tryPassword);
                } else {
                    throw new UnsupportedCallbackException(cb, "Unrecognized Callback");
                }
            }
        };

        LoginContext lc;
        lc = new LoginContext(props.jaasName, null,
                cbHandler,
                props.jaasConfig);
        lc.login();
        return lc.getSubject();
    }
    
    private void stopServer() {
        try {
            cs.stop();
        } catch (IOException e) {
        }
    }

}
