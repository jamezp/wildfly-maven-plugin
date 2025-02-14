/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.plugin.common.AbstractServerConnection;
import org.wildfly.plugin.common.PropertyNames;
import org.wildfly.plugin.common.Utils;
import org.wildfly.plugin.core.MavenRepositoriesEnricher;
import org.wildfly.plugin.tools.server.ServerManager;

/**
 * Execute commands to the running WildFly Application Server.
 * <p/>
 * Commands should be formatted in the same manner CLI commands are formatted.
 * <p/>
 * Executing commands in a batch will rollback all changes if one command fails.
 *
 * <pre>
 *      &lt;batch&gt;true&lt;/batch&gt;
 *      &lt;fail-on-error&gt;false&lt;/fail-on-error&gt;
 *      &lt;commands&gt;
 *          &lt;command&gt;/subsystem=logging/console=CONSOLE:write-attribute(name=level,value=DEBUG)&lt;/command&gt;
 *      &lt;/commands&gt;
 * </pre>
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@Mojo(name = "execute-commands", threadSafe = true)
public class ExecuteCommandsMojo extends AbstractServerConnection {

    /**
     * {@code true} if commands execution should be skipped.
     */
    @Parameter(defaultValue = "false", property = PropertyNames.SKIP)
    private boolean skip;

    /**
     * {@code true} if commands should be executed in a batch or {@code false} if they should be executed one at a
     * time.
     */
    @Parameter(defaultValue = "false", property = PropertyNames.BATCH)
    private boolean batch;

    /**
     * The WildFly Application Server's home directory.
     * <p>
     * This parameter is required when {@code offline} is set to {@code true}. Otherwise this is not required, but
     * should be used for commands such as {@code module add} as they are executed on the local file system.
     * </p>
     */
    @Parameter(alias = "jboss-home", property = PropertyNames.JBOSS_HOME)
    private String jbossHome;

    /**
     * The system properties to be set when executing CLI commands.
     */
    @Parameter(alias = "system-properties")
    private Map<String, String> systemProperties;

    /**
     * The properties files to use when executing CLI scripts or commands.
     */
    @Parameter
    private List<File> propertiesFiles = new ArrayList<>();

    /**
     * The CLI commands to execute.
     */
    @Parameter(property = PropertyNames.COMMANDS)
    private List<String> commands = new ArrayList<>();

    /**
     * The CLI script files to execute.
     */
    @Parameter(property = PropertyNames.SCRIPTS)
    private List<File> scripts = new ArrayList<>();

    /**
     * Indicates whether or not subsequent commands should be executed if an error occurs executing a command. A value of
     * {@code false} will continue processing commands even if a previous command execution results in a failure.
     * <p>
     * Note that this value is ignored if {@code offline} is set to {@code true}.
     * </p>
     */
    @Parameter(alias = "fail-on-error", defaultValue = "true", property = PropertyNames.FAIL_ON_ERROR)
    private boolean failOnError = true;

    /**
     * Indicates the commands should be run in a new process. If the {@code jboss-home} property is not set an attempt
     * will be made to download a version of WildFly to execute commands on. However it's generally considered best
     * practice to set the {@code jboss-home} property if setting this value to {@code true}.
     * <p>
     * Note that if {@code offline} is set to {@code true} this setting really has no effect.
     * </p>
     * <p>
     * <strong>WARNING: </strong> In 3.0.0 you'll be required to set the {@code jboss-home}. An error will occur if
     * this option is {@code true} and the {@code jboss-home} is not set.
     * </p>
     *
     * @since 2.0.0
     */
    @Parameter(defaultValue = "false", property = "wildfly.fork")
    private boolean fork;

    /**
     * Indicates whether or not CLI scrips or commands should be executed in an offline mode. This is useful for using
     * an embedded server or host controller.
     *
     * <p>
     * This does not start an embedded server it instead skips checking if a server is running.
     * </p>
     */
    @Parameter(name = "offline", defaultValue = "false", property = PropertyNames.OFFLINE)
    private boolean offline = false;

    /**
     * Indicates how {@code stdout} and {@code stderr} should be handled for the spawned CLI process. Currently a new
     * process is only spawned if {@code offline} is set to {@code true} or {@code fork} is set to {@code true}. Note
     * that {@code stderr} will be redirected to {@code stdout} if the value is defined unless the value is
     * {@code none}.
     * <div>
     * By default {@code stdout} and {@code stderr} are inherited from the current process. You can change the setting
     * to one of the follow:
     * <ul>
     * <li>{@code none} indicates the {@code stdout} and {@code stderr} stream should not be consumed</li>
     * <li>{@code System.out} or {@code System.err} to redirect to the current processes <em>(use this option if you
     * see odd behavior from maven with the default value)</em></li>
     * <li>Any other value is assumed to be the path to a file and the {@code stdout} and {@code stderr} will be
     * written there</li>
     * </ul>
     * </div>
     */
    @Parameter(name = "stdout", defaultValue = "System.out", property = PropertyNames.STDOUT)
    private String stdout;

    /**
     * The JVM options to pass to the offline process if the {@code offline} configuration parameter is set to
     * {@code true}.
     */
    @Parameter(alias = "java-opts", property = PropertyNames.JAVA_OPTS)
    private String[] javaOpts;

    /**
     * Automatically reloads the server if the commands leave the server in the "reload-required" state. Note a reload
     * will not be done if {@code offline} is set to {@code true}.
     *
     * @since 4.2.1
     */
    @Parameter(alias = "auto-reload", defaultValue = "true", property = PropertyNames.AUTO_RELOAD)
    private boolean autoReload;

    @Inject
    RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession mavenSession;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;

    /**
     * Resolve expressions prior to send the commands to the server.
     *
     * @since 3.0
     */
    @Parameter(alias = "resolve-expressions", defaultValue = "false", property = PropertyNames.RESOLVE_EXPRESSIONS)
    private boolean resolveExpressions;

    @Inject
    private CommandExecutor commandExecutor;

    private MavenRepoManager mavenRepoManager;

    @Override
    public String goal() {
        return "execute-commands";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().debug("Skipping commands execution");
            return;
        }
        MavenRepositoriesEnricher.enrich(mavenSession, project, repositories);
        mavenRepoManager = new MavenArtifactRepositoryManager(repoSystem, session, repositories);
        final CommandConfiguration.Builder cmdConfigBuilder = CommandConfiguration
                .of(this::createClient, this::getClientConfiguration)
                .addCommands(commands)
                .addJvmOptions(javaOpts)
                .addPropertiesFiles(propertiesFiles)
                .addScripts(scripts)
                .addSystemProperties(systemProperties)
                .setBatch(batch)
                .setFailOnError(failOnError)
                .setFork(fork)
                .setJBossHome(jbossHome)
                .setOffline(offline)
                .setAutoReload(autoReload)
                .setStdout(stdout)
                .setTimeout(timeout)
                .setResolveExpression(resolveExpressions);
        // Why is that? fork implies a jboss-home?
        if (fork) {
            cmdConfigBuilder.setJBossHome(getInstallation(buildDir.toPath().resolve(Utils.WILDFLY_DEFAULT_DIR)));
        }
        commandExecutor.execute(cmdConfigBuilder.build(), mavenRepoManager);
        // Check the server state if we're not in offline mode
        if (!offline) {
            try (ModelControllerClient client = createClient()) {
                final String serverState = ServerManager.builder().client(client).build().get(timeout, TimeUnit.SECONDS)
                        .serverState();
                if (!ClientConstants.CONTROLLER_PROCESS_STATE_RUNNING.equals(serverState)) {
                    getLog().warn(String.format(
                            "The server may be in an unexpected state for further interaction. The current state is %s",
                            serverState));
                }
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
                final Log log = getLog();
                log.warn(String.format(
                        "Failed to determine the server-state. The server may be in an unexpected state. Failure: %s",
                        e.getMessage()));
                if (log.isDebugEnabled()) {
                    log.debug(e);
                }
            }
        }
    }

    @Override
    protected int getManagementPort() {
        // Check the java-opts for a management port override
        if (javaOpts != null) {
            for (String opt : javaOpts) {
                if (opt.startsWith("-Djboss.management.http.port=") || opt.startsWith("-Djboss.management.https.port=")) {
                    final int equals = opt.indexOf('=');
                    return Integer.parseInt(opt.substring(equals + 1).trim());
                }
                if (opt.startsWith("-Djboss.socket.binding.port-offset=")) {
                    final int equals = opt.indexOf('=');
                    return super.getManagementPort() + Integer.parseInt(opt.substring(equals + 1).trim());
                }
            }
        }
        return super.getManagementPort();
    }

    @Override
    protected String getManagementHostName() {
        // Check the java-opts for a management port override
        if (javaOpts != null) {
            for (String opt : javaOpts) {
                if (opt.startsWith("-Djboss.bind.address.management=")) {
                    final int equals = opt.indexOf('=');
                    return opt.substring(equals + 1).trim();
                }
            }
        }
        return super.getManagementHostName();
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

    private Path getInstallation(Path installDir) throws MojoFailureException {
        if (jbossHome != null) {
            return Paths.get(jbossHome);
        }
        if (!Files.exists(installDir)) {
            throw new MojoFailureException("No server installed.");
        }
        return installDir;
    }
}
