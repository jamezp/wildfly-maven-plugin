/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2019 Red Hat, Inc., and individual contributors
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

package org.wildfly.plugin.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.core.launcher.CliCommandBuilder;
import org.wildfly.plugin.common.MavenModelControllerClientConfiguration;
import org.wildfly.plugin.common.StandardOutput;
import org.wildfly.plugin.core.ServerHelper;

/**
 * A command executor for executing CLI commands.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@Singleton
@Named
public class CommandExecutor extends AbstractCommandExecutor<CommandConfiguration> {

    /**
     * Executes CLI commands based on the configuration.
     *
     * @param config           the configuration used to execute the CLI commands
     * @param artifactResolver Resolver to retrieve CLI artifact for in-process execution.
     *
     * @throws MojoFailureException   if the JBoss Home directory is required and invalid
     * @throws MojoExecutionException if an error occurs executing the CLI commands
     */
    @Override
    public void execute(final CommandConfiguration config, MavenRepoManager artifactResolver)
            throws MojoFailureException, MojoExecutionException {
        if (config.isOffline()) {
            // The jbossHome is required for offline CLI
            if (!ServerHelper.isValidHomeDirectory(config.getJBossHome())) {
                throw new MojoFailureException("Invalid JBoss Home directory is not valid: " + config.getJBossHome());
            }
            executeInNewProcess(config);
        } else {
            if (config.isFork()) {
                executeInNewProcess(config);
            } else {
                try {
                    executeInProcess(config, artifactResolver);
                } catch (Exception ex) {
                    throw new MojoExecutionException(ex.getLocalizedMessage(), ex);
                }
            }
        }
        if (config.isAutoReload()) {
            // Reload the server if required
            ServerHelper.reloadIfRequired(config.getClient(), config.getTimeout());
        }
    }

    @Override
    protected int executeInNewProcess(final CommandConfiguration config, final Path scriptFile, final StandardOutput stdout)
            throws MojoExecutionException, IOException {
        try (MavenModelControllerClientConfiguration clientConfiguration = config.getClientConfiguration()) {

            final CliCommandBuilder builder = createCommandBuilder(config, scriptFile);
            if (!config.isOffline()) {
                builder.setConnection(clientConfiguration.getController());
            }
            // Configure the authentication config url if defined
            if (clientConfiguration != null && clientConfiguration.getAuthenticationConfigUri() != null) {
                builder.addJavaOption("-Dwildfly.config.url=" + clientConfiguration.getAuthenticationConfigUri().toString());
            }
            return launchProcess(builder, config, stdout);
        }
    }

    private void executeInProcess(final CommandConfiguration config, MavenRepoManager artifactResolver) throws Exception {
        // The jbossHome is not required, but if defined should be valid
        final Path jbossHome = config.getJBossHome();
        if (jbossHome != null && !ServerHelper.isValidHomeDirectory(jbossHome)) {
            throw new MojoFailureException("Invalid JBoss Home directory is not valid: " + jbossHome);
        }
        final Properties currentSystemProperties = System.getProperties();
        try {
            getLogger().debug("Executing commands");
            // Create new system properties with the defaults set to the current system properties
            final Properties newSystemProperties = new Properties(currentSystemProperties);

            // Add the JBoss Home if defined
            if (jbossHome != null) {
                newSystemProperties.setProperty("jboss.home", jbossHome.toString());
                newSystemProperties.setProperty("jboss.home.dir", jbossHome.toString());
            }

            for (Path file : config.getPropertiesFiles()) {
                parseProperties(file, newSystemProperties);
            }

            newSystemProperties.putAll(config.getSystemProperties());

            // Set the system properties for executing commands
            System.setProperties(newSystemProperties);
            LocalCLIExecutor commandContext = null;
            try (ModelControllerClient client = config.getClient()) {
                commandContext = createCommandContext(jbossHome, config.isExpressionResolved(), client, artifactResolver);
                final Collection<String> commands = config.getCommands();
                if (!commands.isEmpty()) {
                    if (config.isBatch()) {
                        commandContext.executeBatch(commands);
                    } else {
                        commandContext.executeCommands(commands, config.isFailOnError());
                    }
                }
                final Collection<Path> scripts = config.getScripts();
                if (!scripts.isEmpty()) {
                    for (Path scriptFile : scripts) {
                        final List<String> cmds = Files.readAllLines(scriptFile, StandardCharsets.UTF_8);
                        if (config.isBatch()) {
                            commandContext.executeBatch(cmds);
                        } else {
                            commandContext.executeCommands(cmds, config.isFailOnError());
                        }
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Could not execute commands.", e);
            } finally {
                if (commandContext != null) {
                    commandContext.close();
                }
            }
        } catch (IOException e) {
            throw new MojoFailureException("Failed to parse properties.", e);
        } finally {
            System.setProperties(currentSystemProperties);
        }
    }

    private LocalCLIExecutor createCommandContext(Path jbossHome, final boolean resolveExpression,
            final ModelControllerClient client,
            MavenRepoManager artifactResolver) throws Exception {
        LocalCLIExecutor commandContext = null;
        try {
            commandContext = new LocalCLIExecutor(jbossHome, resolveExpression, artifactResolver);
            commandContext.bindClient(client);
        } catch (Exception e) {
            // Terminate the session if we've encountered an error
            if (commandContext != null) {
                commandContext.close();
            }
            throw new IllegalStateException("Failed to initialize CLI context", e);
        }
        return commandContext;
    }
}
