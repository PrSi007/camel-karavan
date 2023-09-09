/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.api;

import io.smallrye.mutiny.Multi;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.karavan.code.CodeService;
import org.apache.camel.karavan.code.DockerComposeConverter;
import org.apache.camel.karavan.code.model.DockerComposeService;
import org.apache.camel.karavan.docker.DockerForKaravan;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.infinispan.InfinispanService;
import org.apache.camel.karavan.infinispan.model.ContainerStatus;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.service.ConfigService;
import org.apache.camel.karavan.service.ProjectService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.service.ContainerStatusService.CONTAINER_STATUS;

@Path("/api/container")
public class ContainerResource {

    @Inject
    EventBus eventBus;

    @Inject
    InfinispanService infinispanService;

    @Inject
    KubernetesService kubernetesService;

    @Inject
    DockerForKaravan dockerForKaravan;

    @Inject
    DockerService dockerService;

    @Inject
    ProjectService projectService;

    @Inject
    CodeService codeService;

    @ConfigProperty(name = "karavan.environment")
    String environment;

    private static final Logger LOGGER = Logger.getLogger(ContainerResource.class.getName());

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ContainerStatus> getAllContainerStatuses() throws Exception {
        if (infinispanService.isReady()) {
            return infinispanService.getContainerStatuses().stream()
                    .sorted(Comparator.comparing(ContainerStatus::getProjectId))
                    .collect(Collectors.toList());
        } else {
            return List.of();
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{env}/{type}/{name}")
    public Response manageContainer(@PathParam("env") String env, @PathParam("type") String type, @PathParam("name") String name, JsonObject command) throws Exception {
        if (infinispanService.isReady()) {
            // set container statuses
            setContainerStatusTransit(name, type);
            // exec docker commands
            if (command.containsKey("command")) {
                if (command.getString("command").equalsIgnoreCase("run")) {
                    if (Objects.equals(type, ContainerStatus.ContainerType.devservice.name())) {
                        String code = projectService.getDevServiceCode();
                        DockerComposeService dockerComposeService = DockerComposeConverter.fromCode(code, name);
                        if (dockerComposeService != null) {
                            dockerService.createContainerFromCompose(dockerComposeService, ContainerStatus.ContainerType.devmode);
                            dockerService.runContainer(dockerComposeService.getContainer_name());
                        }
                    } else if (Objects.equals(type, ContainerStatus.ContainerType.project.name())) {
                        DockerComposeService dockerComposeService = projectService.getProjectDockerComposeService(name);
                        if (dockerComposeService != null) {
                            dockerService.createContainerFromCompose(dockerComposeService, ContainerStatus.ContainerType.project);
                            dockerService.runContainer(dockerComposeService.getContainer_name());
                        }
                    } else if (Objects.equals(type, ContainerStatus.ContainerType.devmode.name())) {
//                        TODO: merge with DevMode service
//                        dockerForKaravan.createDevmodeContainer(name, "");
//                        dockerService.runContainer(name);
                    }
                    return Response.ok().build();
                } else if (command.getString("command").equalsIgnoreCase("stop")) {
                    dockerService.stopContainer(name);
                    return Response.ok().build();
                } else if (command.getString("command").equalsIgnoreCase("pause")) {
                    dockerService.pauseContainer(name);
                    return Response.ok().build();
                } else if (command.getString("command").equalsIgnoreCase("delete")) {
                    dockerService.deleteContainer(name);
                    return Response.ok().build();
                }
            }
        }
        return Response.notModified().build();
    }

    private void setContainerStatusTransit(String name, String type){
        ContainerStatus status = infinispanService.getContainerStatus(name, environment, name);
        if (status == null) {
            status = ContainerStatus.createByType(name, environment, ContainerStatus.ContainerType.valueOf(type));
        }
        status.setInTransit(true);
        eventBus.send(CONTAINER_STATUS, JsonObject.mapFrom(status));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{env}")
    public List<ContainerStatus> getContainerStatusesByEnv(@PathParam("env") String env) throws Exception {
        return infinispanService.getContainerStatuses(env).stream()
                .sorted(Comparator.comparing(ContainerStatus::getProjectId))
                .collect(Collectors.toList());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{projectId}/{env}")
    public List<ContainerStatus> getContainerStatusesByProjectAndEnv(@PathParam("projectId") String projectId, @PathParam("env") String env) throws Exception {
        return infinispanService.getContainerStatuses(projectId, env).stream()
                .filter(podStatus -> Objects.equals(podStatus.getType(), ContainerStatus.ContainerType.project))
                .sorted(Comparator.comparing(ContainerStatus::getContainerName))
                .collect(Collectors.toList());
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{env}/{type}/{name}")
    public Response deleteContainer(@PathParam("env") String env, @PathParam("type") String type, @PathParam("name") String name) {
        if (infinispanService.isReady()) {
            // set container statuses
            setContainerStatusTransit(name, type);
            try {
                if (ConfigService.inKubernetes()) {
                    kubernetesService.deletePod(name, kubernetesService.getNamespace());
                } else {
                    dockerService.deleteContainer(name);
                }
                return Response.accepted().build();
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
                return Response.notModified().build();
            }
        }
        return Response.notModified().build();
    }

    // TODO: implement log watch
    @GET
    @Path("/log/watch/{env}/{name}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> getContainerLogWatch(@PathParam("env") String env, @PathParam("name") String name) {
        LOGGER.info("Start sourcing");
        return eventBus.<String>consumer(name + "-" + kubernetesService.getNamespace()).toMulti().map(Message::body);
    }
}