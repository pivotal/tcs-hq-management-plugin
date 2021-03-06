/*
 * Copyright (C) 2009-2015  Pivotal Software, Inc
 *
 * This program is is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.springsource.hq.plugin.tcserver.plugin.discovery;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.management.MBeanServerConnection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.product.DetectionUtil;
import org.hyperic.hq.product.LogTrackPlugin;
import org.hyperic.hq.product.PluginException;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.product.ServerControlPlugin;
import org.hyperic.hq.product.ServerResource;
import org.hyperic.hq.product.ServiceResource;
import org.hyperic.hq.product.jmx.MxServerDetector;
import org.hyperic.sigar.ProcCred;
import org.hyperic.sigar.ProcCredName;
import org.hyperic.sigar.SigarException;
import org.hyperic.util.config.ConfigOption;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.ConfigSchema;
import org.hyperic.util.config.InvalidOptionException;
import org.hyperic.util.config.InvalidOptionValueException;

import com.springsource.hq.plugin.tcserver.plugin.Utils;
import com.springsource.hq.plugin.tcserver.plugin.serverconfig.ServerXmlPropertiesRetriever;
import com.springsource.hq.plugin.tcserver.plugin.serverconfig.XmlPropertiesFileRetriever;
import com.springsource.hq.plugin.tcserver.plugin.wrapper.JmxUtils;
import com.springsource.hq.plugin.tcserver.plugin.wrapper.MxUtilJmxUtils;

public abstract class TcRuntimeDetector extends MxServerDetector {

    public static final String SERVER_RESOURCE_CONFIG_CATALINA_HOME = "catalina.home";

    public static final String SERVER_RESOURCE_CONFIG_CATALINA_BASE = "catalina.base";

    private static final String RELATIVE_PATH_CONF_CATALINA_PROPERTIES = "/conf/catalina.properties";

    private static final String CATALINA_BASE_PROP = "-Dcatalina.base=";

    private static final String CATALINA_HOME_PROP = "-Dcatalina.home=";

    private static final String DEFAULT_CONFIG_TRACK_ENABLE = "DEFAULT_CONFIG_TRACK_ENABLE";

    private static final String DEFAULT_LOG_LEVEL = "DEFAULT_LOG_LEVEL";

    private static final String DEFAULT_LOG_TRACK_ENABLE = "DEFAULT_LOG_TRACK_ENABLE";

    private static final String TCRUNTIME_CONTROL_PROGRAM = "tcruntime-ctl";

    private static final String TCSERVER_CONTROL_PROGRAM = "tcserver-ctl";

    private static final String TCRUNTIME_WIN_CONTROL_PROGRAM = TCRUNTIME_CONTROL_PROGRAM + ".bat";

    private static final String TCSERVER_WIN_CONTROL_PROGRAM = TCSERVER_CONTROL_PROGRAM + ".bat";

    private static final String TCRUNTIME_UNIX_CONTROL_PROGRAM = TCRUNTIME_CONTROL_PROGRAM + ".sh";

    private static final String TCSERVER_UNIX_CONTROL_PROGRAM = TCSERVER_CONTROL_PROGRAM + ".sh";

    protected static final String TOMCAT_7_SPECIFIC_JAR = "lib/tomcat-util.jar";

    protected static final String TOMCAT_8_SPECIFIC_JAR = "lib/tomcat-websocket.jar";

    private static final String DEFAULT_JMX_URL = "service:jmx:rmi:///jndi/rmi://127.0.0.1:6969/jmxrmi";

    private final Log logger = LogFactory.getLog(TcRuntimeDetector.class);

    private final JmxUtils mxUtil = new MxUtilJmxUtils();

    private final ControlScriptParser controlScriptParser = new ControlScriptParser();

    protected void setProductConfig(ServerResource server, ConfigResponse config, long pid) {
        DetectionUtil.populateListeningPorts(pid, config, true);
        super.setProductConfig(server, config);
    }

    /**
     * Retrieves the listener properties from the server.xml file, then creates the jmx.url based on the properties.
     * 
     * @param config The ConfigResponse object.
     * @param basePath The base file path of the configuration, "/conf/server.xml" is added to this.
     * @return Whether the configuration was found and set on the config response object.
     * @throws PluginException
     */
    private boolean configureListenerMxURL(ConfigResponse config, String basePath) throws PluginException {
        boolean found = false;
        XmlPropertiesFileRetriever propertiesRetriever = new ServerXmlPropertiesRetriever();
        try {
            Map<String, String> listenerProperties = propertiesRetriever.getPropertiesFromFile(basePath + "/conf/server.xml", "Listener",
                "className", "com.springsource.tcserver.serviceability.rmi.JmxSocketListener");
            if (!listenerProperties.isEmpty()) {
                String addressProperty = listenerProperties.get("address");
                if (addressProperty == null) {
                    addressProperty = listenerProperties.get("bind");
                }
                String bindAddressValue = getValueFromPropertiesFile(basePath, addressProperty);
                String portValue = getValueFromPropertiesFile(basePath, listenerProperties.get("port"));
                config.setValue(mxUtil.getJmxUrlProperty(), "service:jmx:rmi:///jndi/rmi://" + bindAddressValue + ":" + portValue + "/jmxrmi");
                found = true;
            }
        } catch (PluginException e) {
            // the properties were not accessible
            config.setValue(mxUtil.getJmxUrlProperty(), DEFAULT_JMX_URL);
            logger.warn("Unable to retrieve properties for discovery, using default " + mxUtil.getJmxUrlProperty() + "=" + DEFAULT_JMX_URL);
            if (logger.isDebugEnabled()) {
                logger.debug(e.getMessage(), e);
            }
        }
        return found;
    }

    @Override
    protected final List<ServiceResource> discoverMxServices(MBeanServerConnection server, ConfigResponse serverConfig) throws PluginException {
        @SuppressWarnings("unchecked")
        List<ServiceResource> services = super.discoverMxServices(server, serverConfig);
        for (ServiceResource service : services) {
            enableLogAndConfigTrackPlugins(service);
        }

        return services;
    }

    /**
     * Automatically enables log or config track plugins for services if DEFAULT_LOG_TRACK_ENABLE or
     * DEFAULT_CONFIG_TRACK_ENABLE properties are found
     * 
     * @param service
     */
    private void enableLogAndConfigTrackPlugins(final ServiceResource service) {
        int logLevel = -1;
        boolean enableConfigTrack = false;
        if (Boolean.valueOf((getTypeProperty(service.getType(), DEFAULT_LOG_TRACK_ENABLE)))) {
            logLevel = LogTrackPlugin.LOGLEVEL_INFO;
            if (getTypeProperty(service.getType(), DEFAULT_LOG_LEVEL) != null) {
                try {
                    logLevel = Integer.valueOf(getTypeProperty(service.getType(), DEFAULT_LOG_LEVEL));
                } catch (NumberFormatException e) {
                    logger.warn("Error converting default log level of " + getTypeProperty(service.getType(), DEFAULT_LOG_LEVEL)
                        + " to an integer.  The log level will be set to INFO.");
                }
            }
        }
        if (Boolean.valueOf(getTypeProperty(service.getType(), DEFAULT_CONFIG_TRACK_ENABLE))) {
            enableConfigTrack = true;
        }
        if (logLevel != -1 || enableConfigTrack) {
            service.setMeasurementConfig(new ConfigResponse(), logLevel, enableConfigTrack);
        }
    }

    private String getCatalinaBase(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(CATALINA_BASE_PROP)) {
                return args[i].substring(CATALINA_BASE_PROP.length());
            }
        }
        return null;
    }

    private String getCatalinaHome(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(CATALINA_HOME_PROP)) {
                return args[i].substring(CATALINA_HOME_PROP.length());
            }
        }
        return null;
    }

    private String getBaseDirectoryName(String catalinaBase) {
        return new File(catalinaBase).getName();
    }

    /**
     * Overridden to pass the value of catalina.home to the isInstallTypeVersion method, as opposed to the installpath,
     * which is specified by catalina.base. This is necessary for tc Server, where there may be multiple tc Runtime
     * instances with different catalina.base values sharing the libraries that are used to determine version. The
     * location of those VERSION_FILES needs to be resolved relative to catalina.home. Also overridden to set the
     * control config automatically, so control ops can be executed with default config values
     */
    public final List<ServerResource> getServerResources(ConfigResponse platformConfig) throws PluginException {
        setPlatformConfig(platformConfig);

        List<ServerResource> servers = new ArrayList<ServerResource>();
        @SuppressWarnings("rawtypes")
        List procs = getServerProcessList();

        for (int i = 0; i < procs.size(); i++) {
            MxProcess process = (MxProcess) procs.get(i);

            String[] processArgs = process.getArgs();

            String catalinaBase = getCatalinaBase(processArgs);
            String catalinaHome = getCatalinaHome(processArgs);

            if (!isHqTcRuntime(catalinaBase) && isTcRuntimeInstance(catalinaHome, catalinaBase)) {
                ServerResource serverResource = createTcRuntimeServerResource(catalinaHome, catalinaBase, processArgs, process);
                if (serverResource != null) {
                    servers.add(serverResource);
                }
            }
        }

        return servers;
    }

    /**
     * Get the server name for server resource found.
     * 
     * @param defaultServerName The default server name.
     * @return
     */
    protected String getServerName(String defaultServerName) {
        String serverName = getTypeProperties().getProperty("TC_SERVER_NAME");
        if (serverName == null) {
            serverName = defaultServerName;
        }
        return serverName;
    }

    /**
     * Get the description for server.
     * 
     * @param defaultDescription The default description.
     * @return
     */
    protected String getServerDescription(String defaultDescription) {
        String serverDescription = getTypeProperties().getProperty("TC_SERVER_DESCRIPTION");
        if (serverDescription == null) {
            serverDescription = defaultDescription;
        }
        return serverDescription;
    }

    private ServerResource createTcRuntimeServerResource(String catalinaHome, String catalinaBase, String[] processArgs, MxProcess process)
        throws PluginException {

        String query = PROC_JAVA + ",Args.*.eq=-D" + getProcHomeProperty() + "=" + catalinaBase;

        // Create the server resource
        ServerResource server = newServerResource(catalinaBase);
        server.setDescription(getServerDescription(server.getInstallPath()));
        adjustClassPath(catalinaBase);

        ConfigResponse config = new ConfigResponse();
        ConfigSchema schema = getConfigSchema(getTypeInfo().getName(), ProductPlugin.CFGTYPE_IDX_PRODUCT);

        if (schema != null) {
            ConfigOption option = schema.getOption(PROP_PROCESS_QUERY);

            if (option != null) {
                // Configure process.query
                config.setValue(option.getName(), query);
            }
        }

        if (process.getURL() != null) {
            config.setValue(mxUtil.getJmxUrlProperty(), process.getURL());
        } else {
            String[] args = process.getArgs();
            for (int j = 0; j < args.length; j++) {
                if (configureListenerMxURL(config, catalinaBase)) {
                    break;
                } else if (configureMxURL(config, args[j])) {
                    break;
                } else if (configureLocalMxURL(config, args[j], query)) {
                    // continue as .port might come later
                }
            }
        }

        storeProcessUserAndGroup(process, config);

        config.setValue(SERVER_RESOURCE_CONFIG_CATALINA_BASE, catalinaBase);
        config.setValue(SERVER_RESOURCE_CONFIG_CATALINA_HOME, catalinaHome);

        // default anything not auto-configured
        config.setValue("jmx.password", getJMXPassword(catalinaBase));
        setProductConfig(server, config, process.getPid());
        discoverServerConfig(server, process.getPid());

        server.setMeasurementConfig();
        // must set control config now so user doesn't have to enter config
        // properties prior to executing control actions
        server.setControlConfig();
        server.setName(getPlatformName() + " " + getServerName("tc Runtime") + " " + getBaseDirectoryName(catalinaBase));

        ConfigResponse controlConfig = new ConfigResponse();

        String controlProgram = determineControlProgram(catalinaHome, catalinaBase);
        controlConfig.setValue(ServerControlPlugin.PROP_PROGRAM, controlProgram);
        server.setControlConfig(controlConfig);
        return server;
    }

    private void storeProcessUserAndGroup(MxProcess process, ConfigResponse config) throws InvalidOptionException, InvalidOptionValueException,
        PluginException {
        String user = null;
        String group = null;
        try {
            ProcCredName procCredName = getSigar().getProcCredName(process.getPid());
            user = procCredName.getUser();
            group = procCredName.getGroup();
        } catch (SigarException se) {
            logger.warn("Failed to get proc cred names for tc Server process " + process.getPid());
            if (logger.isDebugEnabled()) {
                logger.debug(se);
            }

            ProcCred procCred;
            try {
                procCred = getSigar().getProcCred(process.getPid());
                user = String.valueOf(procCred.getUid());
                group = String.valueOf(procCred.getGid());
            } catch (SigarException e) {
                logger.warn("Failed to get proc cred for tc Server process " + process.getPid());
            }
        } finally {
            logger.info("Using " + user + "/" + group + " as the user/group for tc Runtime instance process with pid: " + process.getPid());
        }

        config.setValue(Utils.SERVER_RESOURCE_CONFIG_PROCESS_USERNAME, user);
        config.setValue(Utils.SERVER_RESOURCE_CONFIG_PROCESS_GROUP, group);
    }

    private String determineControlProgram(String catalinaHome, String catalinaBase) throws PluginException {
        File installationRoot;
        if (isCombinedLayoutInstance(catalinaHome, catalinaBase)) {
            installationRoot = getInstallationRootOfCombinedLayoutInstance(catalinaBase);
        } else {
            installationRoot = new File(catalinaHome).getParentFile();
        }

        return getControlScript(installationRoot).getAbsolutePath();
    }

    private File getInstallationRootOfCombinedLayoutInstance(String catalinaBase) throws PluginException {
        File controlScript = getControlScript(new File(catalinaBase, "bin"));
        return this.controlScriptParser.getInstallBase(catalinaBase, controlScript);
    }

    private File getControlScript(File controlScriptDir) {
        File runtimeControlScript;

        if (isWin32()) {
            runtimeControlScript = new File(controlScriptDir, TCRUNTIME_WIN_CONTROL_PROGRAM);
            if (!runtimeControlScript.exists()) {
                runtimeControlScript = new File(controlScriptDir, TCSERVER_WIN_CONTROL_PROGRAM);
            }
        } else {
            runtimeControlScript = new File(controlScriptDir, TCRUNTIME_UNIX_CONTROL_PROGRAM);
            if (!runtimeControlScript.exists()) {
                runtimeControlScript = new File(controlScriptDir, TCSERVER_UNIX_CONTROL_PROGRAM);
            }
        }

        return runtimeControlScript;
    }

    private boolean isCombinedLayoutInstance(String catalinaHome, String catalinaBase) {
        return catalinaHome.equals(catalinaBase);
    }

    private String getValueFromPropertiesFile(String basePath, String property) throws PluginException {
        String propertyValue = property;
        if (property.startsWith("${") && property.endsWith("}")) {
            String propertyKey = property.substring(2, property.length() - 1);
            File catalinaProperties = new File(basePath, RELATIVE_PATH_CONF_CATALINA_PROPERTIES);
            InputStream catalinaPropertiesInputStream = null;
            try {
                catalinaPropertiesInputStream = new FileInputStream(catalinaProperties);
                Properties properties = new Properties();
                properties.load(catalinaPropertiesInputStream);
                propertyValue = properties.getProperty(propertyKey);
            } catch (FileNotFoundException e) {
                throw new PluginException("Unable to find " + catalinaProperties.getAbsolutePath(), e);
            } catch (IOException e) {
                throw new PluginException("Failed to read " + catalinaProperties.getAbsolutePath(), e);
            } finally {
                if (catalinaPropertiesInputStream != null) {
                    try {
                        catalinaPropertiesInputStream.close();
                    } catch (IOException ioe) {
                        // Do nothing
                    }
                }
            }
        }
        return propertyValue;
    }

    private String getJMXPassword(String catalinaBase) {
        String password = "";
        BufferedReader reader = null;
        String passwordFilePath = catalinaBase + "/conf/" + "jmxremote.password";
        try {
            File passwordFile = new File(passwordFilePath);
            reader = new BufferedReader(new FileReader(passwordFile));
            String line = reader.readLine();
            while (line != null) {
                if (line.trim().startsWith("admin")) {
                    password = line.substring(5, line.length()).trim();
                }
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            logger.error("Unable to locate file: " + passwordFilePath + ". Error message: " + e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug(e);
            }
        } catch (IOException e) {
            logger.error("Problem loading file: " + passwordFilePath + ". Error message: " + e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug(e);
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return password;
    }

    private boolean isHqTcRuntime(String catalinaBase) {
        File hq = findVersionFile(new File(catalinaBase), Pattern.compile("hq-common.*\\.jar"));
        return hq != null;
    }

    protected boolean isTcRuntimeInstance(String catalinaHome, String catalinaBase) {
        if (catalinaHome == null) {
            return false;
        }

        if (catalinaBase == null) {
            return false;
        }

        if (isInstallTypeVersion(catalinaHome) || isInstallTypeVersion(catalinaBase)) {
            return true;
        }

        return false;
    }

    abstract boolean isMyTomcatVersion(String catalinaHome, String catalinaBase);
}
