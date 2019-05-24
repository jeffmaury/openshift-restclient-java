/*******************************************************************************
 * Copyright (c) 2015-2019 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Red Hat, Inc.
 ******************************************************************************/

package com.openshift.internal.restclient;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openshift.internal.restclient.model.DeploymentConfig;
import com.openshift.internal.restclient.model.ModelNodeBuilder;
import com.openshift.internal.restclient.model.Pod;
import com.openshift.internal.restclient.model.ReplicationController;
import com.openshift.internal.restclient.model.Service;
import com.openshift.internal.restclient.model.build.BuildConfigBuilder;
import com.openshift.internal.restclient.model.properties.ResourcePropertyKeys;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;
import com.openshift.restclient.IWatcher;
import com.openshift.restclient.NotFoundException;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.capability.IBinaryCapability;
import com.openshift.restclient.images.DockerImageURI;
import com.openshift.restclient.model.IBuildConfig;
import com.openshift.restclient.model.IDeploymentConfig;
import com.openshift.restclient.model.IPod;
import com.openshift.restclient.model.IProject;
import com.openshift.restclient.model.IReplicationController;
import com.openshift.restclient.model.IResource;
import com.openshift.restclient.model.IService;
import com.openshift.restclient.model.deploy.DeploymentTriggerType;

public class IntegrationTestHelper implements ResourcePropertyKeys {

    public static final long TEST_TIMEOUT = 6 * 1000;
    public static final long TEST_LONG_TIMEOUT = 3 * 60 * 1000;
    
    public static final long MILLISECONDS_PER_SECOND = 1000;
    public static final long MILLISECONDS_PER_MIN = MILLISECONDS_PER_SECOND * 60;

    private static final String KEY_INTEGRATION_TEST_PROJECT = "integrationtest.project";
    private static final String KEY_SERVER_URL = "serverURL";
    private static final String KEY_PASSWORD = "default.clusteradmin.password";
    private static final String KEY_USER = "default.clusteradmin.user";
    private static final String KEY_OPENSHIFT_LOCATION = "ocbinary.location";

    private static final String INTEGRATIONTEST_PROPERTIES = "/openshiftv3IntegrationTest.properties";

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationTestHelper.class);
    private static final String POD_NAME_DEPLOY = "deploy";
    private static final String POD_DOCKER_REGISTRY = "docker-registry";
    private static final String DEFAULT_PROJECT = "default";
    
    private final Properties prop;

    public IntegrationTestHelper() {
        this.prop = loadProperties(INTEGRATIONTEST_PROPERTIES);
    }

    public IClient createClient() {
        return new ClientBuilder(getServer()).build();
    }

    private String getServer() {
        return prop.getProperty(KEY_SERVER_URL);
    }

    public IClient createClientForBasicAuth() {
        return new ClientBuilder(getServer())
                .withUserName(getDefaultClusterAdminUser())
                .withPassword(getDefaultClusterAdminPassword())
                .build();
    }

    public String getIntegrationTestNamespace() {
        return prop.getProperty(KEY_INTEGRATION_TEST_PROJECT);
    }

    public static String getDefaultNamespace() {
        return DEFAULT_PROJECT;
    }

    public IProject createProject(String name, IClient client) {
        IResource request = client.getResourceFactory().stub(ResourceKind.PROJECT_REQUEST, name);
        return (IProject) client.create(request);
    }

    public IProject getOrCreateIntegrationTestProject(IClient client) {
        return getOrCreateProject(getIntegrationTestNamespace(), client);
    }

    /**
     * Returns the existing project if it is present. If not, it'll create a new project and return
     * it.
     * 
     * @param name the name of the project
     * @param client the client to be used
     * @return the existing/new project
     */
    public IProject getOrCreateProject(String name, IClient client) {
        IProject project = null;
        try {
            project = client.get(ResourceKind.PROJECT, name, "");
        } catch (NotFoundException e ) {
            project = createProject(name, client);
        }
        return project;
    }

    public static String appendRandom(String string) {
        return String.format("%s-%s", string, new Random().nextInt(9999));
    }

    public static boolean isDockerRegistry(IPod pod) {
        return pod != null
                && pod.getName().startsWith(POD_DOCKER_REGISTRY);
    }
    
    public IPod getDockerRegistryPod(Collection<IPod> pods) {
        return pods.stream()
                .filter(p -> isDockerRegistry(p))
                .findFirst()
                .orElse(null);
    }


    public <R extends IResource> Collection<R> createResources(IClient client, R... resources) {
        if (ArrayUtils.isEmpty(resources)) {
            return Collections.emptyList();
        }
        return Stream.of(resources)
                .map(r -> client.create(r))
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }

    public <R extends IResource> R createResource(IClient client, R resource) {
        if (resource == null) {
            return null;
        }
        return client.create(resource);
    }

    public IPod createPod(IClient client, String namespace, String name) {
        return client.create(stubPod(client, namespace, name));
    }

    /**
     * Stub a pod definition to the openshift/hello-openshift image for purposes of
     * testing.
     * 
     * @param client the client to use
     * @param namespace the namespace to stub the pod for
     * @param name the name of the pod
     * 
     * @return a pod definition that needs to be further created using the client
     */
    public IPod stubPod(IClient client, String namespace, String name) {
        ModelNode builder = new ModelNodeBuilder().set(ResourcePropertyKeys.KIND, ResourceKind.POD)
                .set(ResourcePropertyKeys.METADATA_NAME, name)
                .set(ResourcePropertyKeys.METADATA_NAMESPACE, namespace)
                .add("spec.containers",
                        new ModelNodeBuilder().set(ResourcePropertyKeys.NAME, "hello-openshift")
                                .set("image", "openshift/hello-openshift")
                                .add("ports", new ModelNodeBuilder().set("containerPort", 8080).set("protocol", "TCP")))
                .build();
        return new Pod(builder, client, new HashMap<>());
    }

    public IDeploymentConfig stubDeploymentConfig(IClient client, String namespace, String name) {
        IDeploymentConfig dc = new ResourceFactory(client).stub(ResourceKind.DEPLOYMENT_CONFIG, name, namespace);
        dc.setReplicas(1);
        dc.setReplicaSelector("foo", "bar");
        dc.addContainer(dc.getName(), 
                new DockerImageURI("openshift/hello-openshift"), 
                new HashSet<>(),
                Collections.emptyMap(), 
                Collections.emptyList());
        dc.addTrigger(DeploymentTriggerType.CONFIG_CHANGE);
        return dc;
    }

    public IReplicationController stubReplicationController(IClient client, String namespace, String name) {
        IReplicationController rc = new ResourceFactory(client).create("v1", ResourceKind.REPLICATION_CONTROLLER);
        ((ReplicationController) rc).setName(name);
        ((ReplicationController) rc).setNamespace(namespace);
        rc.setReplicas(1);
        rc.setReplicaSelector("foo", "bar");
        rc.addContainer(rc.getName(), 
                new DockerImageURI("openshift/hello-openshift"), 
                new HashSet<>(),
                Collections.emptyMap(), 
                Collections.emptyList());
        return rc;
    }

    public IBuildConfig stubBuildConfig(IClient client, String namespace, String name, String gitUrl, Map<String, String> labels) {
        BuildConfigBuilder builder = new BuildConfigBuilder(client);
        builder.named(name)
            .inNamespace(namespace);
        if (!StringUtils.isEmpty(gitUrl)) {
            builder
                .fromGitSource()
                .fromGitUrl(gitUrl)
                .end();
        }
        builder.usingSourceStrategy()
            .fromDockerImage("centos/ruby-22-centos7:latest")
            .end()
            .toImageStreamTag("ruby-hello-world:latest")
            .withLabels(labels);
        return builder.build();
    }

    public IService stubService(IClient client, String namespace, String name, int remotePort, int port, String selector) {
        Service service = client.getResourceFactory().create("v1", ResourceKind.SERVICE);
        service.setName(name);
        service.setNamespace(namespace);
        service.setTargetPort(remotePort);
        service.setPort(port);
        service.setSelector("name", selector);
        return service;
    }
    
    /**
     * Loads the properties from the given {@code propertyFileName}, then overrides
     * from the System properties if any was given (this is a convenient way to
     * override the default settings and avoid conflicting with the properties file
     * in git)
     * 
     * @return the properties to use in the test
     * @throws IOException an io exception
     */
    private Properties loadProperties(final String propertyFileName) {
        final Properties properties = new Properties();
        try {
            properties.load(IntegrationTestHelper.class.getResourceAsStream(propertyFileName));
        } catch (IOException e) {
            e.printStackTrace();
            fail("Failed to load properties from file " + INTEGRATIONTEST_PROPERTIES + ": " + e.getMessage());
        }
        overrideIfExists(properties, KEY_SERVER_URL);
        overrideIfExists(properties, KEY_INTEGRATION_TEST_PROJECT);
        overrideIfExists(properties, KEY_OPENSHIFT_LOCATION);
        overrideIfExists(properties, KEY_USER);
        overrideIfExists(properties, KEY_PASSWORD);
        return properties;
    }

    private void overrideIfExists(final Properties properties, final String propertyName) {
        // then override with the VM arguments (if any)
        final String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null) {
            properties.setProperty(propertyName, propertyValue);
        }
    }

    public String getOpenShiftLocation() {
        return prop.getProperty(KEY_OPENSHIFT_LOCATION);
    }

    public void setOpenShiftBinarySystemProperty() {
        System.setProperty(IBinaryCapability.OPENSHIFT_BINARY_LOCATION, getOpenShiftLocation());
    }
    
    public String getDefaultClusterAdminUser() {
        return prop.getProperty(KEY_USER);
    }

    public String getDefaultClusterAdminPassword() {
        return prop.getProperty(KEY_PASSWORD);
    }

    public String getServerUrl() {
        return getServer();
    }

    public <R extends IResource> void cleanUpResources(IClient client, R... resources) {
        if (ArrayUtils.isEmpty(resources)) {
            return;
        }
        Stream.of(resources).forEach(resource -> cleanUpResource(client, resource));
    }

    public <R extends IResource> void cleanUpResources(IClient client, Collection<R> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        resources.forEach(resource -> cleanUpResource(client, resource));
    }

    public <R extends IResource> void cleanUpResource(IClient client, R resource) {
        if (client == null || resource == null) {
            LOG.debug("Skipping cleanup as client to {} or resource {} are null", 
                    client == null ? "" : client.getBaseURL(), 
                    resource == null ? "" : resource.getName());
            return;
        }
        LOG.debug(String.format("Deleting resource: %s", resource));
        cleanUpResource(client, resource.getKind(), resource.getNamespaceName(), resource.getName());
    }

    public void cleanUpResource(IClient client, String kind, String namespace, String name) {
        if (client == null || StringUtils.isEmpty(name) || StringUtils.isEmpty(namespace)) {
            LOG.debug("Skipping cleanup as client to {} or resource {} are null", 
                    client == null ? "" : client.getBaseURL(), 
                    StringUtils.isEmpty(name) ? "" : name);
            return;
        }
        try {
            LOG.debug(String.format("Deleting resource: %s", name));
            client.delete(kind, namespace, name);
        } catch (Exception e) {
            LOG.warn("Exception deleting", e);
        }
    }

    /**
     * Wait for the resource to exist for cases where the test is faster then the
     * server in reconciling its existence;
     * 
     * @return The resource or null if the maxWaitMillis was exceeded or the
     *         resource doesnt exist
     */
    public IResource waitForResource(IClient client, String kind, String namespace, String name,
            long maxWaitMillis) {
        return waitForResource(client, kind, namespace, name, maxWaitMillis, new ReadyConditional() {
            @Override
            public boolean isReady(IResource resource) {
                return resource != null;
            }

        });
    }

    public <R extends IResource> R waitForResource(IClient client, R resource,
            long maxWaitMillis, ReadyConditional conditional) {
        return waitForResource(client, resource.getKind(), resource.getNamespaceName(), resource.getName(), maxWaitMillis, conditional);
    }

    /**
     * Wait for the resource to exist for cases where the test is faster then the
     * server in reconciling its existence;
     * 
     */
    public <R extends IResource> R waitForResource(IClient client, String kind, String namespace, String name,
            long maxWaitMillis, ReadyConditional conditional) {
        R resource = null;
        final long timeout = System.currentTimeMillis() + maxWaitMillis;
        do {
            try {
                resource = client.get(kind, name, namespace);
                if (resource != null && conditional != null) {
                    if (conditional.isReady(resource)) {
                        return resource;
                    }
                    resource = null;
                }
            } catch (NotFoundException e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        } while (resource == null && System.currentTimeMillis() <= timeout);
        return resource;
    }

    /**
     * Interface that can evaluate a resource to determine if its ready
     */
    public static interface ReadyConditional {

        /**
         * 
         * @return true if the resource is 'ready'
         */
        boolean isReady(IResource resource);
    }

    public boolean waitForDisappearance(IClient client, IResource resource, long maxWaitMillis) {
        final long timeout = System.currentTimeMillis() + maxWaitMillis;
        do {
            try {
                resource = client.get(resource.getKind(), resource.getName(), resource.getNamespaceName());
                Thread.sleep(1000);
            } catch (NotFoundException e) {
                return true;
            } catch (InterruptedException e) {
                return false;
            }
        } while (resource == null && System.currentTimeMillis() <= timeout);
        return false;
    }

    public void stopWatcher(IWatcher watcher) {
        if (watcher != null) {
            watcher.stop();
        }
    }

    public boolean isDeployPod(IPod pod) {
        return pod.getName().endsWith(POD_NAME_DEPLOY);
    }
}
