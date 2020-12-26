package fr.loghub.jmxagent;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.management.InstanceNotFoundException;
import javax.management.ObjectName;
import javax.management.RuntimeOperationsException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import fr.jrds.jmxagent.JmxStarter;

public class TestJmxConnection {

    private static final String hostip;
    static {
        try {
            hostip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ex) {
            throw new UncheckedIOException(ex);
        }
    }
    private static final String loopbackip = InetAddress.getLoopbackAddress().getHostAddress();
    private final int port = tryGetPort();

    static {
        Map<String, String> env = new HashMap<>();

        env.put("sun.rmi.transport.tcp.readTimeout", "500");
        env.put("sun.rmi.transport.connectionTimeout", "500");
        env.put("sun.rmi.transport.proxy.connectTimeout", "500");
        env.put("java.rmi.server.disableHttp", "true");
        env.put("sun.rmi.transport.tcp.handshakeTimeout", "500");
        env.put("sun.rmi.transport.tcp.responseTimeout", "500");

        System.getProperties().putAll(env);
    }

    @BeforeClass
    public static void startJmx() throws Exception {
        Locale.setDefault(Locale.ENGLISH);
    }

    @After
    public void stopJmx() throws IOException {
        JmxStarter.stop();
    }

    @Test
    public void loadConf1() throws Exception {
        String configStr = "port=" + port + File.pathSeparator + "hostname=" + hostip;
        JmxStarter.premain(configStr);
        connect(hostip, loopbackip);
    }

    @Test
    public void loadConf2() throws Exception {
        String configStr = "port=" +  port + File.pathSeparator + "hostname=" + loopbackip;
        JmxStarter.premain(configStr);
        connect(loopbackip, hostip);
    }

    @Test
    public void checkStrict() throws Exception {
        String configStr = "port=" +  port + File.pathSeparator + "hostname=" + hostip;
        JmxStarter.premain(configStr);
        JMXConnector jmxc = connect(hostip, loopbackip);
        SecurityException ex = Assert.assertThrows(SecurityException.class, () -> {
            jmxc.getMBeanServerConnection().createMBean("", new ObjectName(""));
        });
        Assert.assertEquals("Access denied! Invalid access level for requested MBeanServer operation.", ex.getMessage());
    }

    @Test
    public void checkNotScrit() throws Exception {
        String configStr = "port=" +  port + File.pathSeparator + "strict=false" + File.pathSeparator + "hostname=" + hostip;
        JmxStarter.premain(configStr);
        JMXConnector jmxc = connect(hostip, loopbackip);
        RuntimeOperationsException ex = Assert.assertThrows(RuntimeOperationsException.class, () -> {
            jmxc.getMBeanServerConnection().createMBean("", new ObjectName(""));
        });
        Assert.assertEquals("Exception occurred during MBean creation", ex.getMessage());
    }

    @Test
    public void loadExplicitJaas() throws Exception {
        String configStr = "port=" +  port + File.pathSeparator + "hostname=" + hostip + File.pathSeparator
                + "jaasConfiguration=" + getClass().getClassLoader().getResource("jaas.config").getFile() + File.pathSeparator
                + "jaasName=jmxAuthentication";
        JmxStarter.premain(configStr);
        Assert.assertThrows(SecurityException.class, () -> {
            connect(hostip, loopbackip);
        });
        JMXConnector jmxc = connect(hostip, loopbackip, Collections.singletonMap("jmx.remote.credentials", new String[] {"login" , "password"}));
        Assert.assertNotEquals(0, jmxc.getMBeanServerConnection().getDomains().length);
    }

    private JMXConnector connect(String ip1, String ip2) throws IOException, InstanceNotFoundException {
        return connect(ip1, ip2, Collections.emptyMap());
    }

    private JMXConnector connect(String ip1, String ip2, Map<String, ?> env) throws IOException, InstanceNotFoundException {
        JMXServiceURL url = 
                new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + ip1 + ":" + port + "/jmxrmi");
        JMXConnector jmxc = JMXConnectorFactory.connect(url, env);
        String cnxId = jmxc.getConnectionId();
        Assert.assertTrue(cnxId.contains(ip1));
        Assert.assertFalse(cnxId.contains(ip2));
        return jmxc;
    }

    public static int tryGetPort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

}
