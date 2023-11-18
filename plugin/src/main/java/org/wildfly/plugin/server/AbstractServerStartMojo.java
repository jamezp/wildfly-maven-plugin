/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2023 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.plugin.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.core.launcher.CommandBuilder;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.StandaloneCommandBuilder;
import org.wildfly.plugin.common.AbstractServerConnection;
import org.wildfly.plugin.common.Environment;
import org.wildfly.plugin.common.PropertyNames;
import org.wildfly.plugin.common.StandardOutput;
import org.wildfly.plugin.common.Utils;
import org.wildfly.plugin.core.GalleonUtils;
import org.wildfly.plugin.core.MavenRepositoriesEnricher;
import org.wildfly.plugin.core.ServerHelper;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public abstract class AbstractServerStartMojo extends AbstractServerConnection {

    @Component
    protected RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    protected RepositorySystemSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    protected List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession mavenSession;
    /**
     * The target directory the application to be deployed is located.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File targetDir;

    /**
     * The WildFly Application Server's home directory. If not used, WildFly will be downloaded.
     */
    @Parameter(alias = "jboss-home", property = PropertyNames.JBOSS_HOME)
    protected String jbossHome;

    /**
     * The feature pack location. See the <a href="https://docs.wildfly.org/galleon/#_feature_pack_location">documentation</a>
     * for details on how to format a feature pack location.
     * <p>
     * Note that if you define the version in the feature pack location, e.g. {@code #26.1.1.Final}, the {@code version}
     * configuration parameter should be left blank.
     * </p>
     */
    @Parameter(alias = "feature-pack-location", property = PropertyNames.WILDFLY_FEATURE_PACK_LOCATION)
    private String featurePackLocation;

    /**
     * The version of the WildFly default server to install in case no jboss-home has been set
     * and no server has previously been provisioned.
     * <p>
     * The latest stable version is resolved if left blank.
     * </p>
     */
    @Parameter(property = PropertyNames.WILDFLY_VERSION)
    private String version;

    /**
     * The directory name inside the buildDir where to provision the default server.
     * By default the server is provisioned into the 'server' directory.
     *
     * @since 3.0
     */
    @Parameter(alias = "provisioning-dir", property = PropertyNames.WILDFLY_PROVISIONING_DIR, defaultValue = Utils.WILDFLY_DEFAULT_DIR)
    private String provisioningDir;

    /**
     * The modules path or paths to use. A single path can be used or multiple paths by enclosing them in a paths
     * element.
     */
    @Parameter(alias = "modules-path", property = PropertyNames.MODULES_PATH)
    private ModulesPath modulesPath;

    /**
     * The JVM options to use.
     */
    @Parameter(alias = "java-opts", property = PropertyNames.JAVA_OPTS)
    private String[] javaOpts;

    /**
     * Options passed to JBoss Modules. This is useful for things like Java Agents where you need to start the server
     * with an agent.
     */
    @Parameter(alias = "module-options", property = PropertyNames.MODULE_OPTS)
    private String[] moduleOptions;

    /**
     * The {@code JAVA_HOME} to use for launching the server.
     */
    @Parameter(alias = "java-home", property = PropertyNames.JAVA_HOME)
    private String javaHome;

    /**
     * Starts the server with debugging enabled.
     */
    @Parameter(property = "wildfly.debug", defaultValue = "false")
    private boolean debug;

    /**
     * Sets the hostname to listen on for debugging. An {@code *} means all hosts.
     */
    @Parameter(property = "wildfly.debug.host", defaultValue = "*")
    private String debugHost;

    /**
     * Sets the port the debugger should listen on.
     */
    @Parameter(property = "wildfly.debug.port", defaultValue = "8787")
    private int debugPort;

    /**
     * Indicates whether the server should suspend itself until a debugger is attached.
     */
    @Parameter(property = "wildfly.debug.suspend", defaultValue = "false")
    private boolean debugSuspend;

    /**
     * The path to the system properties file to load.
     */
    @Parameter(alias = "properties-file", property = PropertyNames.PROPERTIES_FILE)
    private String propertiesFile;

    /**
     * The timeout value to use when starting the server.
     */
    @Parameter(alias = "startup-timeout", defaultValue = "60", property = PropertyNames.STARTUP_TIMEOUT)
    private long startupTimeout;

    /**
     * The arguments to be passed to the server.
     */
    @Parameter(alias = "server-args", property = PropertyNames.SERVER_ARGS)
    private String[] serverArgs;

    /**
     * Set to {@code true} if you want to skip this goal, otherwise {@code false}.
     */
    @Parameter(defaultValue = "false", property = PropertyNames.SKIP)
    protected boolean skip;

    /**
     * The users to add to the server.
     */
    @Parameter(alias = "add-user", property = "wildfly.add-user")
    private AddUser addUser;

    /**
     * Specifies the environment variables to be passed to the process being started.
     * <div>
     *
     * <pre>
     * &lt;env&gt;
     *     &lt;HOME&gt;/home/wildfly/&lt;/HOME&gt;
     * &lt;/env&gt;
     * </pre>
     *
     * </div>
     */
    @Parameter
    private Map<String, String> env;

    private final AtomicBoolean initialized = new AtomicBoolean();

    protected MavenRepoManager mavenRepoManager;

    protected void init() throws MojoExecutionException {
        // Setting the mavenRepoManager is not thread-safe, however creating it more than once won't hurt anything
        if (initialized.compareAndSet(false, true)) {
            MavenRepositoriesEnricher.enrich(mavenSession, project, repositories);
            mavenRepoManager = createMavenRepoManager();
        }
    }

    protected MavenRepoManager createMavenRepoManager() throws MojoExecutionException {
        return new MavenArtifactRepositoryManager(repoSystem, session, repositories);
    }

    protected ServerContext startServer(final ServerType serverType) throws MojoExecutionException, MojoFailureException {
        final Log log = getLog();
        init();

        // Validate the environment
        final Path jbossHome = provisionIfRequired(targetDir.toPath().resolve(provisioningDir));
        if (!ServerHelper.isValidHomeDirectory(jbossHome)) {
            throw new MojoExecutionException(String.format("JBOSS_HOME '%s' is not a valid directory.", jbossHome));
        }

        // Determine how stdout should be consumed
        try {
            final StandardOutput out = standardOutput();
            // Create the server and close the client after the start. The process will continue running even after
            // the maven process may have been finished
            try (ModelControllerClient client = createClient()) {
                if (ServerHelper.isStandaloneRunning(client) || ServerHelper.isDomainRunning(client)) {
                    throw new MojoExecutionException(String.format("%s server is already running?", serverType));
                }
                final CommandBuilder commandBuilder = createCommandBuilder(jbossHome);
                log.info(String.format("%s server is starting up.", serverType));
                final Launcher launcher = Launcher.of(commandBuilder)
                        .setRedirectErrorStream(true);
                if (env != null) {
                    launcher.addEnvironmentVariables(env);
                }
                out.getRedirect().ifPresent(launcher::redirectOutput);

                final Process process = launcher.launch();
                // Note that if this thread is started and no shutdown goal is executed this stop the stdout and stderr
                // from being logged any longer. The user was warned in the documentation.
                out.startConsumer(process);
                if (serverType == ServerType.DOMAIN) {
                    ServerHelper.waitForDomain(process, DomainClient.Factory.create(client), startupTimeout);
                } else {
                    ServerHelper.waitForStandalone(process, client, startupTimeout);
                }
                if (!process.isAlive()) {
                    throw new MojoExecutionException("The process has been terminated before the start goal has completed.");
                }
                return new ServerContext() {
                    @Override
                    public Process process() {
                        return process;
                    }

                    @Override
                    public CommandBuilder commandBuilder() {
                        return commandBuilder;
                    }

                    @Override
                    public Path jbossHome() {
                        return jbossHome;
                    }
                };
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("The server failed to start", e);
        }
    }

    protected abstract CommandBuilder createCommandBuilder(final Path jbossHome) throws MojoExecutionException;

    protected StandardOutput standardOutput() throws IOException {
        return StandardOutput.parse(null, false);
    }

    /**
     * Allows the {@link #javaOpts} to be set as a string. The string is assumed to be space delimited.
     *
     * @param value a spaced delimited value of JVM options
     */
    @SuppressWarnings("unused")
    public void setJavaOpts(final String value) {
        if (value != null) {
            javaOpts = value.split("\\s+");
        }
    }

    /**
     * Allows the {@link #moduleOptions} to be set as a string. The string is assumed to be space delimited.
     *
     * @param value a spaced delimited value of JBoss Modules options
     */
    @SuppressWarnings("unused")
    public void setModulesOptions(final String value) {
        if (value != null) {
            moduleOptions = value.split("\\s+");
        }
    }

    protected StandaloneCommandBuilder createStandaloneCommandBuilder(final Path jbossHome, final String serverConfig)
            throws MojoExecutionException {
        final StandaloneCommandBuilder commandBuilder = StandaloneCommandBuilder.of(jbossHome)
                .setJavaHome(javaHome)
                .addModuleDirs(modulesPath.getModulePaths());

        // Set the JVM options
        if (Utils.isNotNullOrEmpty(javaOpts)) {
            commandBuilder.setJavaOptions(javaOpts);
        }
        if (debug) {
            commandBuilder.addJavaOptions(String.format("-agentlib:jdwp=transport=dt_socket,server=y,suspend=%s,address=%s:%d",
                    debugSuspend ? "y" : "n", debugHost, debugPort));
        }

        if (serverConfig != null) {
            commandBuilder.setServerConfiguration(serverConfig);
        }

        if (propertiesFile != null) {
            commandBuilder.setPropertiesFile(propertiesFile);
        }

        if (serverArgs != null) {
            commandBuilder.addServerArguments(serverArgs);
        }

        if (Utils.isNotNullOrEmpty(moduleOptions)) {
            commandBuilder.setModuleOptions(moduleOptions);
        }

        final Path javaHomePath = (this.javaHome == null ? Paths.get(System.getProperty("java.home"))
                : Paths.get(this.javaHome));
        if (Environment.isModularJvm(javaHomePath)) {
            commandBuilder.addJavaOptions(Environment.getModularJvmArguments());
        }

        // Print some server information
        final Log log = getLog();
        log.info("JAVA_HOME : " + commandBuilder.getJavaHome());
        log.info("JBOSS_HOME: " + commandBuilder.getWildFlyHome());
        log.info("JAVA_OPTS : " + Utils.toString(commandBuilder.getJavaOptions(), " "));
        try {
            addUsers(commandBuilder.getWildFlyHome(), commandBuilder.getJavaHome());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to add users", e);
        }
        return commandBuilder;
    }

    protected DomainCommandBuilder createDomainCommandBuilder(final Path jbossHome, final String domainConfig,
            final String hostConfig) throws MojoExecutionException {
        final Path javaHome = (this.javaHome == null ? Paths.get(System.getProperty("java.home")) : Paths.get(this.javaHome));
        final DomainCommandBuilder commandBuilder = DomainCommandBuilder.of(jbossHome, javaHome)
                .addModuleDirs(modulesPath.getModulePaths());

        // Set the JVM options
        if (Utils.isNotNullOrEmpty(javaOpts)) {
            commandBuilder.setProcessControllerJavaOptions(javaOpts)
                    .setHostControllerJavaOptions(javaOpts);
        }

        if (domainConfig != null) {
            commandBuilder.setDomainConfiguration(domainConfig);
        }

        if (hostConfig != null) {
            commandBuilder.setHostConfiguration(hostConfig);
        }

        if (propertiesFile != null) {
            commandBuilder.setPropertiesFile(propertiesFile);
        }

        if (serverArgs != null) {
            commandBuilder.addServerArguments(serverArgs);
        }

        // Workaround for WFCORE-4121
        if (Environment.isModularJvm(javaHome)) {
            commandBuilder.addHostControllerJavaOptions(Environment.getModularJvmArguments());
            commandBuilder.addProcessControllerJavaOptions(Environment.getModularJvmArguments());
        }

        // Print some server information
        final Log log = getLog();
        log.info("JAVA_HOME : " + commandBuilder.getJavaHome());
        log.info("JBOSS_HOME: " + commandBuilder.getWildFlyHome());
        log.info("JAVA_OPTS : " + Utils.toString(commandBuilder.getHostControllerJavaOptions(), " "));
        try {
            addUsers(commandBuilder.getWildFlyHome(), commandBuilder.getJavaHome());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to add users", e);
        }
        return commandBuilder;
    }

    protected Path provisionIfRequired(final Path installDir) throws MojoFailureException, MojoExecutionException {
        if (jbossHome != null) {
            // we do not need to download WildFly
            return Paths.get(jbossHome);
        }
        try {
            if (!Files.exists(installDir)) {
                getLog().info("Provisioning default server in " + installDir);
                GalleonUtils.provision(installDir, resolveFeaturePackLocation(), version, mavenRepoManager);
            }
            return installDir;
        } catch (ProvisioningException ex) {
            throw new MojoFailureException(ex.getLocalizedMessage(), ex);
        }
    }

    /**
     * Checks the current state of the server. If the server is in a state of
     * {@link ClientConstants#CONTROLLER_PROCESS_STATE_RESTART_REQUIRED}, the process is restarted and a new
     * {@link ServerContext} is returned. If the server is in a stat of
     * {@link ClientConstants#CONTROLLER_PROCESS_STATE_RELOAD_REQUIRED}, the server will be reloaded and wait until
     * the server is running. If the server is in any other state, other than
     * {@link ClientConstants#CONTROLLER_PROCESS_STATE_RUNNING}, a warning message is logged to let the user know
     * the state is unknown.
     *
     * @param client  the client used to communicate with the server
     * @param context the current server context
     * @return a new context if a restart was required, otherwise the same context
     * @throws IOException            if an error occurs communicating with the server
     * @throws MojoExecutionException if a failure occurs checking the state or reloading the server
     * @throws MojoFailureException   if a failure occurs checking the state or reloading the server
     */
    protected ServerContext actOnServerState(final ModelControllerClient client, final ServerContext context)
            throws IOException, MojoExecutionException, MojoFailureException {
        final String serverState = ServerHelper.serverState(client);
        if (ClientConstants.CONTROLLER_PROCESS_STATE_RESTART_REQUIRED.equals(serverState)) {
            // Shutdown the server
            ServerHelper.shutdownStandalone(client, timeout);
            // Restart the server process
            return startServer(ServerType.STANDALONE);
        } else if (ClientConstants.CONTROLLER_PROCESS_STATE_RELOAD_REQUIRED.equals(serverState)) {
            ServerHelper.executeReload(client, Operations.createOperation("reload"));
            try {
                ServerHelper.waitForStandalone(context.process(), client, timeout);
            } catch (InterruptedException | TimeoutException e) {
                throw new MojoExecutionException("Failed to wait for standalone server after a reload.", e);
            }
        } else if (!ClientConstants.CONTROLLER_PROCESS_STATE_RUNNING.equals(serverState)) {
            getLog().warn(String.format(
                    "The server may be in an unexpected state for further interaction. The current state is %s", serverState));
        }
        return context;
    }

    private void addUsers(final Path wildflyHome, final Path javaHome) throws IOException {
        if (addUser != null && addUser.hasUsers()) {
            getLog().info("Adding users: " + addUser);
            addUser.addUsers(wildflyHome, javaHome);
        }
    }

    private String resolveFeaturePackLocation() {
        return featurePackLocation == null ? getDefaultFeaturePackLocation() : featurePackLocation;
    }

    /**
     * Returns the default feature pack location if not defined in the configuration.
     *
     * @return the default feature pack location
     */
    protected String getDefaultFeaturePackLocation() {
        return "wildfly@maven(org.jboss.universe:community-universe)";
    }
}
