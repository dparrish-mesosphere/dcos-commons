package com.mesosphere.sdk.framework;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.mesos.Protos;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.mesosphere.sdk.dcos.Capabilities;
import com.mesosphere.sdk.dcos.DcosConstants;
import com.mesosphere.sdk.offer.Constants;
import com.mesosphere.sdk.scheduler.MesosEventClient;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.scheduler.plan.Status;
import com.mesosphere.sdk.storage.Persister;
import com.mesosphere.sdk.storage.PersisterException;
import com.mesosphere.sdk.storage.StorageError.Reason;
import com.mesosphere.sdk.testutils.TestConstants;

import static org.mockito.Mockito.*;

public class FrameworkRunnerTest {

    @Mock private SchedulerConfig mockSchedulerConfig;
    @Mock private FrameworkConfig mockFrameworkConfig;
    @Mock private Capabilities mockCapabilities;
    @Mock private MesosEventClient mockMesosEventClient;
    @Mock private Persister mockPersister;

    @Before
    public void beforeEach() {
        MockitoAnnotations.initMocks(this);
        Capabilities.overrideCapabilities(mockCapabilities);

        when(mockSchedulerConfig.getMarathonName()).thenReturn("test-marathon");
    }

    @Test
    public void testEmptyDeployPlan() {
        // Sanity check...
        Assert.assertEquals(Constants.DEPLOY_PLAN_NAME, FrameworkRunner.EMPTY_DEPLOY_PLAN.getName());
        Assert.assertEquals(Status.COMPLETE, FrameworkRunner.EMPTY_DEPLOY_PLAN.getStatus());
        Assert.assertTrue(FrameworkRunner.EMPTY_DEPLOY_PLAN.getChildren().isEmpty());
    }

    @Test
    public void testFinishedUninstall() throws Exception {
        FrameworkRunner runner = new FrameworkRunner(mockSchedulerConfig, mockFrameworkConfig, false, false);
        when(mockSchedulerConfig.isUninstallEnabled()).thenReturn(true);
        when(mockPersister.get("FrameworkID")).thenThrow(new PersisterException(Reason.NOT_FOUND, "hi"));
        when(mockFrameworkConfig.getFrameworkName()).thenReturn("frameworkName");
        Exception abort = new IllegalStateException("Aborting HTTP server run");
        // Don't actually run the HTTP server -- it won't exit:
        when(mockSchedulerConfig.getApiServerPort()).thenThrow(abort);
        try {
            runner.registerAndRunFramework(mockPersister, mockMesosEventClient);
            Assert.fail("Expected abort exception to be thrown");
        } catch (IllegalStateException ex) {
            Assert.assertSame(abort, ex);
        }
        // Shouldn't have used the regular endpoints. Instead should have used stub endpoints:
        verify(mockMesosEventClient, never()).getHTTPEndpoints();
        verify(mockPersister).recursiveDelete("/");
    }

    @Test
    public void testMinimalFrameworkInfoInitial() {
        EnvStore envStore = EnvStore.fromMap(getMinimalMap());
        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
        FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);

        FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

        Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.empty());
        Assert.assertEquals("/path/to/test-service", info.getName());
        Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
        Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
        Assert.assertTrue(info.getCheckpoint());
        Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
        Assert.assertFalse(info.hasId());
        checkRole(Optional.of("path__to__test-service-role"), info);
        Assert.assertEquals(0, info.getRolesCount());
        Assert.assertEquals(0, info.getCapabilitiesCount());
        Assert.assertFalse(info.hasWebuiUrl());
    }

    @Test
    public void testMinimalFrameworkInfoRelaunch() {
        EnvStore envStore = EnvStore.fromMap(getMinimalMap());
        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
        FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);

        FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

        Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
        Assert.assertEquals("/path/to/test-service", info.getName());
        Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
        Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
        Assert.assertTrue(info.getCheckpoint());
        Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
        Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
        checkRole(Optional.of("path__to__test-service-role"), info);
        Assert.assertEquals(0, info.getRolesCount());
        Assert.assertEquals(0, info.getCapabilitiesCount());
        Assert.assertFalse(info.hasWebuiUrl());
    }

    @Test
    public void testExhaustiveFrameworkInfo() {
        Map<String, String> env = getMinimalMap();
        env.put("FRAMEWORK_PRINCIPAL", "custom-principal");
        env.put("FRAMEWORK_USER", "custom-user");
        env.put("FRAMEWORK_PRERESERVED_ROLES", "role1,role2,role3");
        env.put("FRAMEWORK_WEB_URL", "custom-url");
        EnvStore envStore = EnvStore.fromMap(env);
        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
        FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);

        when(mockCapabilities.supportsGpuResource()).thenReturn(true);
        when(mockCapabilities.supportsPreReservedResources()).thenReturn(true);
        when(mockCapabilities.supportsDomains()).thenReturn(true);
        when(mockCapabilities.supportsGpuResource()).thenReturn(true);

        FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, true, true);
        Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
        Assert.assertEquals("/path/to/test-service", info.getName());
        Assert.assertEquals("custom-user", info.getUser());
        Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
        Assert.assertTrue(info.getCheckpoint());
        Assert.assertEquals("custom-principal", info.getPrincipal());
        Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
        checkRole(Optional.empty(), info);
        Assert.assertTrue(info.getRolesList().containsAll(Arrays.asList("path__to__test-service-role", "role1", "role2", "role3")));
        Assert.assertEquals(Arrays.asList(
                getCapability(Protos.FrameworkInfo.Capability.Type.MULTI_ROLE),
                getCapability(Protos.FrameworkInfo.Capability.Type.GPU_RESOURCES),
                getCapability(Protos.FrameworkInfo.Capability.Type.RESERVATION_REFINEMENT),
                getCapability(Protos.FrameworkInfo.Capability.Type.REGION_AWARE)), info.getCapabilitiesList());
        Assert.assertEquals("custom-url", info.getWebuiUrl());
    }


    /*
     *  Matrix for verification of the following variables.
     *  {Pre-reserved-role, Service-role, Enforce-role, Migration-Mode}
     *  Pre-reserved-role: Does the service have static reservations via pre-reserved-role
     *  Service-role: Is the service using a the new quota-role (T) or legacy-role (F)
     *  Enforce-role: Is the service in a group where the role is enforced.
     *  Migration-Mode: Is the service set to migrate quotas. 
     *   
     *  The following combinations are invalid:
     *  XFTX - Here pre-reserved-role and migration-mode are irrelevant. You cannot have
     *  service-role as false (read in legacy-role mode) with enforce-role set to true.
     */
   
    @Test
    public void testMesosRole_TTTT() {
       
      final String MESOS_ALLOCATION_ROLE = "path";

      Map<String, String> env = getMinimalMap();
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "true");
      env.put("FRAMEWORK_PRERESERVED_ROLES", "role1,role2,role3");
      env.put("ENABLE_ROLE_MIGRATION", "true");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);
        
      when(mockCapabilities.supportsPreReservedResources()).thenReturn(true);

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      Assert.assertTrue(info.getRolesList().containsAll(Arrays.asList(MESOS_ALLOCATION_ROLE, "path__to__test-service-role", "role1", "role2", "role3")));
      Assert.assertEquals(5, info.getRolesCount());
      Assert.assertEquals(2, info.getCapabilitiesCount()); //MULTI_ROLE gets enabled.
      Assert.assertFalse(info.hasWebuiUrl());     
    }
     
    @Test
    public void testMesosRole_FTFT() {
       
      final String MESOS_ALLOCATION_ROLE = "path";

      Map<String, String> env = getMinimalMap();
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "false");
      env.put("ENABLE_ROLE_MIGRATION", "true");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);
        

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      Assert.assertTrue(info.getRolesList().containsAll(Arrays.asList(MESOS_ALLOCATION_ROLE, "path__to__test-service-role")));
      Assert.assertEquals(2, info.getRolesCount());
      Assert.assertEquals(1, info.getCapabilitiesCount()); //MULTI_ROLE gets enabled.
      Assert.assertFalse(info.hasWebuiUrl());     
    }
     
    @Test
    public void testMesosRole_FFFT() {
       
      final String SERVICE_ROLE = "path";
      final String MESOS_ALLOCATION_ROLE = "slave_public";

      Map<String, String> env = getMinimalMap();
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "false");
      env.put("ENABLE_ROLE_MIGRATION", "true");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);
        

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      Assert.assertTrue(info.getRolesList().containsAll(Arrays.asList(SERVICE_ROLE, "path__to__test-service-role")));
      Assert.assertEquals(2, info.getRolesCount());
      Assert.assertEquals(1, info.getCapabilitiesCount()); //MULTI_ROLE gets enabled.
      Assert.assertFalse(info.hasWebuiUrl());     
    }
    
    @Test
    public void testMesosRole_TTTF() {
       
      final String SERVICE_ROLE = "path";
      final String MESOS_ALLOCATION_ROLE = "path";

      Map<String, String> env = getMinimalMap();
      env.put("FRAMEWORK_PRERESERVED_ROLES", "role1,role2,role3");
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "true");
      env.put("ENABLE_ROLE_MIGRATION", "false");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);
        
      when(mockCapabilities.supportsPreReservedResources()).thenReturn(true);

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      Assert.assertTrue(info.getRolesList().containsAll(Arrays.asList(SERVICE_ROLE, "role1", "role2", "role3")));
      Assert.assertEquals(4, info.getRolesCount());
      Assert.assertEquals(2, info.getCapabilitiesCount()); //MULTI_ROLE gets enabled.
      Assert.assertFalse(info.hasWebuiUrl());     
    }
     
    @Test
    public void testMesosRole_FTFF() {
       
      final String SERVICE_ROLE = "path";
      final String MESOS_ALLOCATION_ROLE = "path";

      Map<String, String> env = getMinimalMap();
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "false");
      env.put("ENABLE_ROLE_MIGRATION", "false");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      checkRole(Optional.of(SERVICE_ROLE), info);
      Assert.assertEquals(0, info.getRolesCount());
      Assert.assertEquals(0, info.getCapabilitiesCount());
      Assert.assertFalse(info.hasWebuiUrl());     
    }
        
    @Test
    public void testMesosRole_TFFF() {
       
      final String SERVICE_ROLE = "path__to__test-service-role";
      final String MESOS_ALLOCATION_ROLE = "slave_public";

      Map<String, String> env = getMinimalMap();
      env.put("FRAMEWORK_PRERESERVED_ROLES", "role1,role2,role3");
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "false");
      env.put("ENABLE_ROLE_MIGRATION", "false");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);
      
      when(mockCapabilities.supportsPreReservedResources()).thenReturn(true);

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      Assert.assertTrue(info.getRolesList().containsAll(Arrays.asList(SERVICE_ROLE, "role1", "role2", "role3")));
      Assert.assertEquals(4, info.getRolesCount());
      Assert.assertEquals(2, info.getCapabilitiesCount()); //MULTI_ROLE gets enabled.
      Assert.assertFalse(info.hasWebuiUrl());     
    }

    @Test
    public void testMesosRole_FFFF() {
      final String SERVICE_ROLE = "path__to__test-service-role";
      final String MESOS_ALLOCATION_ROLE = "slave_public";

      Map<String, String> env = getMinimalMap();
      env.put("MESOS_ALLOCATION_ROLE", MESOS_ALLOCATION_ROLE);
      env.put("MARATHON_APP_ENFORCE_GROUP_ROLE", "false");
      env.put("ENABLE_ROLE_MIGRATION", "false");
      EnvStore envStore = EnvStore.fromMap(env);

      SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(envStore);
      FrameworkConfig frameworkConfig = FrameworkConfig.fromEnvStore(envStore);

      FrameworkRunner runner = new FrameworkRunner(schedulerConfig, frameworkConfig, false, false);

      Protos.FrameworkInfo info = runner.getFrameworkInfo(Optional.of(TestConstants.FRAMEWORK_ID));
      Assert.assertEquals("/path/to/test-service", info.getName());
      Assert.assertEquals(DcosConstants.DEFAULT_SERVICE_USER, info.getUser());
      Assert.assertEquals(1209600, info.getFailoverTimeout(), 0.1);
      Assert.assertTrue(info.getCheckpoint());
      Assert.assertEquals("/path/to/test-service-principal", info.getPrincipal());
      Assert.assertEquals(TestConstants.FRAMEWORK_ID, info.getId());
      checkRole(Optional.of(SERVICE_ROLE), info);
      Assert.assertEquals(0, info.getRolesCount());
      Assert.assertEquals(0, info.getCapabilitiesCount());
      Assert.assertFalse(info.hasWebuiUrl());     
    }
    
    
    private static Protos.FrameworkInfo.Capability getCapability(Protos.FrameworkInfo.Capability.Type type) {
        return Protos.FrameworkInfo.Capability.newBuilder().setType(type).build();
    }

    @SuppressWarnings("deprecation")
    private static void checkRole(Optional<String> expectedRole, Protos.FrameworkInfo info) {
        if (expectedRole.isPresent()) {
            Assert.assertEquals(expectedRole.get(), info.getRole());
        } else {
            Assert.assertFalse(info.hasRole());
        }
    }

    private static Map<String, String> getMinimalMap() {
        Map<String, String> map = new HashMap<>();
        map.put("FRAMEWORK_NAME", "/path/to/test-service");
        // Required by SchedulerConfig:
        map.put("PACKAGE_NAME", "test-package");
        map.put("PACKAGE_VERSION", "1.5");
        map.put("PACKAGE_BUILD_TIME_EPOCH_MS", "1234567890");
        return map;
    }
}
