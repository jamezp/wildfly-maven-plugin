/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.dmr.ModelNode;
import org.junit.Test;
import org.wildfly.plugin.common.ServerOperations;
import org.wildfly.plugin.tests.AbstractWildFlyServerMojoTest;
import org.wildfly.plugin.tests.TestEnvironment;

/**
 * @author <a href="mailto:sven-torben@sven-torben.de">Sven-Torben Janus</a>
 */
public class FailOnErrorTest extends AbstractWildFlyServerMojoTest {

    @Test
    public void testExecuteCommandsFailOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands", "execute-commands-failOnError-pom.xml");
        setValidSession(executeCommandsMojo);
        try {
            executeCommandsMojo.execute();
            fail("MojoExecutionException expected.");
        } catch (MojoExecutionException e) {
            assertEquals("org.jboss.as.cli.CommandLineException", e.getCause().getClass().getName());
        }
        final ModelNode address = ServerOperations.createAddress("system-property", "propertyFailOnError");
        final ModelNode op = ServerOperations.createReadAttributeOperation(address, "value");
        final ModelNode result = executeOperation(op);
        try {
            assertEquals("initial value", ServerOperations.readResultAsString(result));
        } finally {
            // Remove the system property
            executeOperation(ServerOperations.createRemoveOperation(address));
        }
    }

    @Test
    public void testExecuteCommandsForkOpFailOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands",
                "execute-commands-fork-op-failOnError-pom.xml");
        // Set the JBoss home field so commands will be executed in a new process
        setValue(executeCommandsMojo, "jbossHome", TestEnvironment.WILDFLY_HOME.toString());

        try {
            executeCommandsMojo.execute();
            fail("MojoExecutionException expected.");
        } catch (MojoExecutionException ignore) {
        }

        final ModelNode address = ServerOperations.createAddress("system-property", "propertyFailOnError");
        final ModelNode op = ServerOperations.createReadAttributeOperation(address, "value");
        final ModelNode result = executeOperation(op);
        try {
            assertEquals("initial value", ServerOperations.readResultAsString(result));
        } finally {
            // Remove the system property
            executeOperation(ServerOperations.createRemoveOperation(address));
        }
    }

    @Test
    public void testExecuteCommandsForkCmdFailOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands",
                "execute-commands-fork-cmd-failOnError-pom.xml");
        // Set the JBoss home field so commands will be executed in a new process
        setValue(executeCommandsMojo, "jbossHome", TestEnvironment.WILDFLY_HOME.toString());

        try {
            executeCommandsMojo.execute();
            fail("MojoExecutionException expected.");
        } catch (MojoExecutionException ignore) {
        }
        // Read the attribute
        final ModelNode address = ServerOperations.createAddress("system-property", "propertyFailOnError.in.try");
        final ModelNode result = executeOperation(ServerOperations.createReadAttributeOperation(address, "value"));

        try {
            assertEquals("inside catch", ServerOperations.readResultAsString(result));
        } finally {
            // Remove the system property
            executeOperation(ServerOperations.createRemoveOperation(address));
        }
    }

    @Test
    public void testExecuteCommandsContinueOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands", "execute-commands-continueOnError-pom.xml");
        setValidSession(executeCommandsMojo);
        executeCommandsMojo.execute();

        // Read the attribute
        final ModelNode address = ServerOperations.createAddress("system-property", "propertyContinueOnError");
        final ModelNode op = ServerOperations.createReadAttributeOperation(address, "value");
        final ModelNode result = executeOperation(op);

        try {
            assertEquals("continue on error", ServerOperations.readResultAsString(result));
        } finally {
            // Clean up the property
            executeOperation(ServerOperations.createRemoveOperation(address));
        }
    }

    @Test
    public void testExecuteCommandsForkContinueOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands",
                "execute-commands-fork-continueOnError-pom.xml");
        // Set the JBoss home field so commands will be executed in a new process
        setValue(executeCommandsMojo, "jbossHome", TestEnvironment.WILDFLY_HOME.toString());

        executeCommandsMojo.execute();

        // Read the attribute
        final ModelNode address = ServerOperations.createAddress("system-property", "propertyContinueOnError");
        final ModelNode op = ServerOperations.createReadAttributeOperation(address, "value");
        final ModelNode result = executeOperation(op);

        // Read the attribute
        final ModelNode inTryAddress = ServerOperations.createAddress("system-property", "propertyContinueOnError.in.try");
        final ModelNode inTryResult = executeOperation(ServerOperations.createReadAttributeOperation(inTryAddress, "value"));

        try {
            assertEquals("continue on error", ServerOperations.readResultAsString(result));
            assertEquals("inside catch", ServerOperations.readResultAsString(inTryResult));

            // The invalid module directory should not exist
            final Path baseModuleDir = Paths.get(TestEnvironment.WILDFLY_HOME.toString(), "modules", "org", "wildfly", "plugin",
                    "tests");
            final Path invalidModuleDir = baseModuleDir.resolve("invalid");
            assertTrue(String.format("Expected %s to not exist.", invalidModuleDir), Files.notExists(invalidModuleDir));

            // The valid module directory should exist
            final Path moduleDir = baseModuleDir.resolve("main");
            assertTrue(String.format("Expected %s to exist.", moduleDir), Files.exists(moduleDir));
            assertTrue("Expected the module.xml to exist in " + moduleDir, Files.exists(moduleDir.resolve("module.xml")));
            assertTrue("Expected the test.jar to exist in " + moduleDir, Files.exists(moduleDir.resolve("test.jar")));
        } finally {
            // Clean up the property
            executeOperation(ServerOperations.createRemoveOperation(address));
            executeOperation(ServerOperations.createRemoveOperation(inTryAddress));

            // Remove the module
            deleteRecursively(TestEnvironment.WILDFLY_HOME.resolve("modules").resolve("org"));
        }
    }

    @Test
    public void testExecuteCommandScriptFailOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands", "execute-script-failOnError-pom.xml");
        setValidSession(executeCommandsMojo);
        try {
            executeCommandsMojo.execute();
            fail("MojoExecutionException expected.");
        } catch (MojoExecutionException e) {
            assertEquals("org.jboss.as.cli.CommandLineException", e.getCause().getClass().getName());
        }
        final ModelNode address = ServerOperations.createAddress("system-property", "scriptFailOnError");
        final ModelNode op = ServerOperations.createReadAttributeOperation(address, "value");
        final ModelNode result = executeOperation(op);
        try {
            assertEquals("initial value", ServerOperations.readResultAsString(result));
        } finally {
            // Remove the system property
            executeOperation(ServerOperations.createRemoveOperation(address));
        }
    }

    @Test
    public void testExecuteCommandScriptContinueOnError() throws Exception {

        final Mojo executeCommandsMojo = lookupMojoAndVerify("execute-commands", "execute-script-continueOnError-pom.xml");
        setValidSession(executeCommandsMojo);
        executeCommandsMojo.execute();

        // Read the attribute
        final ModelNode address = ServerOperations.createAddress("system-property", "scriptContinueOnError");
        final ModelNode op = ServerOperations.createReadAttributeOperation(address, "value");
        final ModelNode result = executeOperation(op);

        try {
            assertEquals("continue on error", ServerOperations.readResultAsString(result));
        } finally {
            // Clean up the property
            executeOperation(ServerOperations.createRemoveOperation(address));
        }
    }

}
