/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2016 Red Hat, Inc., and individual contributors
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

package org.wildfly.plugin.core;

import java.util.Set;

/**
 * Represents a default description for a deployment.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 *
 * @deprecated moved to new https://github.com/wildfly/wildfly-plugin-tools project
 */
@Deprecated(forRemoval = true)
@SuppressWarnings("WeakerAccess")
public interface DeploymentDescription {

    /**
     * Returns the name for this deployment.
     *
     * @return the name for this deployment
     */
    String getName();

    /**
     * Returns the server groups for this deployment.
     *
     * @return a set of server groups for this deployment
     */
    Set<String> getServerGroups();
}
