package com.lonelight.contentplanner.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lonelight.contentplanner.agent.runtime.AgentRunner;
import com.lonelight.contentplanner.controller.PlanController;
import com.lonelight.contentplanner.service.AgentTaskService;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.SavedPlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.service.impl.AgentTaskServiceImpl;
import com.lonelight.contentplanner.service.impl.PlanServiceImpl;
import com.lonelight.contentplanner.service.impl.SavedPlanServiceImpl;
import com.lonelight.contentplanner.service.impl.TaskPersistenceServiceImpl;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/** Enforces the project's service contracts without starting Spring or a database. */
class ServiceArchitectureTest {
    @Test
    void serviceContractsAreInterfacesWithImplementationsInTheImplPackage() throws Exception {
        for (Class<?> contract :
                List.of(
                        PlanService.class,
                        AgentTaskService.class,
                        SavedPlanService.class,
                        TaskPersistenceService.class)) {
            assertThat(contract.isInterface()).as(contract.getName()).isTrue();
            Class<?> implementation =
                    Class.forName(
                            contract.getPackageName()
                                    + ".impl."
                                    + contract.getSimpleName()
                                    + "Impl");
            assertThat(implementation).isAssignableTo(contract);
            assertThat(implementation.isAnnotationPresent(Service.class)).isTrue();
            assertThat(contract.isAnnotationPresent(Service.class)).isFalse();
        }
    }

    @Test
    void persistenceServicesExposeMpContractsAndReuseMpImplementations() {
        assertThat(AgentTaskService.class).isAssignableTo(IService.class);
        assertThat(SavedPlanService.class).isAssignableTo(IService.class);
        assertThat(AgentTaskServiceImpl.class).isAssignableTo(ServiceImpl.class);
        assertThat(SavedPlanServiceImpl.class).isAssignableTo(ServiceImpl.class);
    }

    @Test
    void applicationLayersInjectServiceInterfacesThroughConstructors() {
        for (Class<?> component :
                List.of(
                        PlanController.class,
                        AgentRunner.class,
                        PlanServiceImpl.class,
                        TaskPersistenceServiceImpl.class)) {
            assertThat(component.getDeclaredConstructors()).hasSize(1);
            for (var field : component.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as(component.getSimpleName() + "." + field.getName())
                        .isTrue();
                if (field.getType()
                        .getPackageName()
                        .startsWith("com.lonelight.contentplanner.service")) {
                    assertThat(field.getType().isInterface())
                            .as("service dependency " + field.getName())
                            .isTrue();
                }
            }
        }
    }
}
