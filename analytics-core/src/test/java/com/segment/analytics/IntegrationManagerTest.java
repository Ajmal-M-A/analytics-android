package com.segment.analytics;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Pair;
import com.segment.analytics.internal.AbstractIntegration;
import com.segment.analytics.internal.model.payloads.AliasPayload;
import com.segment.analytics.internal.model.payloads.BasePayload;
import com.segment.analytics.internal.model.payloads.GroupPayload;
import com.segment.analytics.internal.model.payloads.IdentifyPayload;
import com.segment.analytics.internal.model.payloads.ScreenPayload;
import com.segment.analytics.internal.model.payloads.TrackPayload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static com.segment.analytics.Analytics.LogLevel.NONE;
import static com.segment.analytics.TestUtils.SynchronousExecutor;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.CREATED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.DESTROYED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.PAUSED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.RESUMED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.SAVE_INSTANCE;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.STARTED;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type.STOPPED;
import static com.segment.analytics.IntegrationManager.IntegrationManagerHandler;
import static com.segment.analytics.IntegrationManager.IntegrationOperation;
import static com.segment.analytics.IntegrationManager.PayloadOperation;
import static com.segment.analytics.TestUtils.PROJECT_SETTINGS_JSON_SAMPLE;
import static com.segment.analytics.TestUtils.createContext;
import static com.segment.analytics.TestUtils.mockApplication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.Mock;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(RobolectricTestRunner.class) @Config(emulateSdk = 18, manifest = Config.NONE)
public class IntegrationManagerTest {

  @Mock Client client;
  @Mock Stats stats;
  @Mock ProjectSettings.Cache projectSettingsCache;
  ExecutorService networkExecutor;
  @Mock ProjectSettings projectSettings;
  @Mock AbstractIntegration mockIntegration;
  Context context;
  IntegrationManager integrationManager;

  static ProjectSettings createProjectSettings(String json) throws IOException {
    Map<String, Object> map = Cartographer.INSTANCE.fromJson(json);
    return ProjectSettings.create(map);
  }

  @Before public void setUp() throws IOException {
    initMocks(this);

    context = mockApplication();
    when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)) //
        .thenReturn(PERMISSION_DENIED);
    when(projectSettingsCache.get()).thenReturn(projectSettings);

    when(mockIntegration.key()).thenReturn("foo");

    networkExecutor = Mockito.spy(new SynchronousExecutor());

    integrationManager =
        new IntegrationManager(context, client, networkExecutor, Cartographer.INSTANCE, stats,
            projectSettingsCache, NONE);

    integrationManager.bundledIntegrations.clear();
    integrationManager.integrations.clear();
  }

  @After public void tearDown() {
    assertThat(ShadowLog.getLogs()).isEmpty();
  }

  @Test public void checkForBundledIntegration() {
    integrationManager //
        .checkBundledIntegration("com.segment.analytics.integrations.EmptyIntegration");

    assertThat(integrationManager.bundledIntegrations).hasSize(1).containsKey("empty");
    assertThat(integrationManager.integrations).hasSize(1);
  }

  @Test public void throwsExceptionWhenLoadingInvalidIntegration() {
    try {
      integrationManager //
          .checkBundledIntegration("com.segment.analytics.integrations.InvalidIntegration");
      fail("loading an integration with no-args constructor should throw exception.");
    } catch (RuntimeException e) {
      assertThat(e).hasMessage("Could not instantiate class.")
          .hasRootCauseExactlyInstanceOf(NoSuchMethodException.class);
    }

    assertThat(integrationManager.bundledIntegrations).isEmpty();
    assertThat(integrationManager.integrations).isEmpty();
  }

  @Test public void skipsFetchingSettingsIfDisconnected() {
    NetworkInfo networkInfo = mock(NetworkInfo.class);
    when(networkInfo.isConnectedOrConnecting()).thenReturn(false);
    ConnectivityManager connectivityManager = mock(ConnectivityManager.class);
    when(connectivityManager.getActiveNetworkInfo()).thenReturn(networkInfo);
    when(context.getSystemService(CONNECTIVITY_SERVICE)).thenReturn(connectivityManager);
    integrationManager.integrationManagerHandler.removeMessages(
        IntegrationManagerHandler.REQUEST_FETCH_SETTINGS);

    integrationManager.performFetchSettings();

    assertThat(integrationManager.integrationManagerHandler.hasMessages(
        IntegrationManagerHandler.REQUEST_FETCH_SETTINGS)) //
        .isTrue();
  }

  @Test public void failureDuringFetchingSettingsSchedulesRetry() throws IOException {
    integrationManager.integrationManagerHandler.removeMessages(
        IntegrationManagerHandler.REQUEST_FETCH_SETTINGS);
    when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)).thenReturn(PERMISSION_DENIED);
    doThrow(new IOException("mock")).when(client).fetchSettings();

    integrationManager.performFetchSettings();

    assertThat(integrationManager.integrationManagerHandler.hasMessages(
        IntegrationManagerHandler.REQUEST_FETCH_SETTINGS)) //
        .isTrue();
  }

  @Test public void fetchSettingsSuccessfullyDispatchesInitialize() throws IOException {
    integrationManager.integrationManagerHandler //
        .removeMessages(IntegrationManagerHandler.REQUEST_INITIALIZE_INTEGRATIONS);
    when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)).thenReturn(PERMISSION_DENIED);
    when(client.fetchSettings()).thenReturn(new Client.Connection(mock(HttpURLConnection.class),
        new ByteArrayInputStream(PROJECT_SETTINGS_JSON_SAMPLE.getBytes()),
        mock(OutputStream.class)) {
      @Override public void close() throws IOException {
        super.close();
      }
    });

    integrationManager.performFetchSettings();

    verify(projectSettingsCache).set(any(ProjectSettings.class));
    assertThat(integrationManager.integrationManagerHandler //
        .hasMessages(IntegrationManagerHandler.REQUEST_INITIALIZE_INTEGRATIONS)).isTrue();
  }

  @Test public void run() {
    // Verify that an operation is forwarded to all integrations.
    integrationManager.integrations.add(mockIntegration);
    integrationManager.integrations.add(mockIntegration);
    integrationManager.integrations.add(mockIntegration);

    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().build();
    integrationManager.run(new PayloadOperation(trackPayload));
    verify(mockIntegration, times(3)).track(trackPayload);

    Activity activity = mock(Activity.class);
    integrationManager.run(new ActivityLifecyclePayload(DESTROYED, activity, null));
    verify(mockIntegration, times(3)).onActivityDestroyed(activity);
  }

  @Test public void runPayloadOperation() {
    // Verifies that each operation calls the right method on an integration.
    Activity activity = mock(Activity.class);
    Bundle bundle = mock(Bundle.class);

    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().build();
    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);
    verify(mockIntegration).track(trackPayload);

    ScreenPayload screenPayload = new TestUtils.ScreenPayloadBuilder().build();
    new PayloadOperation(screenPayload).run(mockIntegration, projectSettings);
    verify(mockIntegration).screen(screenPayload);

    AliasPayload aliasPayload = new TestUtils.AliasPayloadBuilder().build();
    new PayloadOperation(aliasPayload).run(mockIntegration, projectSettings);
    verify(mockIntegration).alias(aliasPayload);

    IdentifyPayload identifyPayload = new TestUtils.IdentifyPayloadBuilder().build();
    new PayloadOperation(identifyPayload).run(mockIntegration, projectSettings);
    verify(mockIntegration).identify(identifyPayload);

    GroupPayload groupPayload = new TestUtils.GroupPayloadBuilder().build();
    new PayloadOperation(groupPayload).run(mockIntegration, projectSettings);
    verify(mockIntegration).group(groupPayload);

    new ActivityLifecyclePayload(CREATED, activity, bundle).run(mockIntegration, projectSettings);
    verify(mockIntegration).onActivityCreated(activity, bundle);

    new ActivityLifecyclePayload(STARTED, activity, null).run(mockIntegration, projectSettings);
    verify(mockIntegration).onActivityStarted(activity);

    new ActivityLifecyclePayload(RESUMED, activity, null).run(mockIntegration, projectSettings);
    verify(mockIntegration).onActivityResumed(activity);

    new ActivityLifecyclePayload(PAUSED, activity, null).run(mockIntegration, projectSettings);
    verify(mockIntegration).onActivityPaused(activity);

    new ActivityLifecyclePayload(STOPPED, activity, null).run(mockIntegration, projectSettings);
    verify(mockIntegration).onActivityStopped(activity);

    new ActivityLifecyclePayload(SAVE_INSTANCE, activity, bundle).run(mockIntegration,
        projectSettings);
    verify(mockIntegration).onActivitySaveInstanceState(activity, bundle);

    new ActivityLifecyclePayload(DESTROYED, activity, null).run(mockIntegration, projectSettings);
    verify(mockIntegration).onActivityDestroyed(activity);

    new IntegrationManager.FlushOperation().run(mockIntegration, projectSettings);
    verify(mockIntegration).flush();
  }

  @Test public void trackWithoutPlan() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration).track(trackPayload);
  }

  @Test public void trackPlanEnablesEvent() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();
    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "          \"All\": true\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration).track(trackPayload);
  }

  @Test public void trackPlanDisablesEvent() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();
    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": false,\n"
        + "        \"integrations\": {\n"
        + "          \"All\": true,\n"
        + "          \"foo\": true\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration, only()).key();
  }

  @Test public void trackPlanEnablesIntegration() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();
    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "          \"All\": false,\n"
        + "          \"foo\": true\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration).track(trackPayload);
  }

  @Test public void trackPlanNoIntegrations() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();
    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": true\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration).track(trackPayload);
  }

  @Test public void trackPlanEmptyIntegrations() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();
    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration).track(trackPayload);
  }

  @Test public void trackPlanDisablesIntegration() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();

    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "          \"All\": true,\n"
        + "          \"foo\": false\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration, times(2)).key();
    verifyNoMoreInteractions(mockIntegration);
  }

  @Test public void trackPlanDisablesAllIntegrationsByDefault() throws IOException {
    TrackPayload trackPayload = new TestUtils.TrackPayloadBuilder().event("bar").build();
    ProjectSettings projectSettings = createProjectSettings("{\n"
        + "  \"plan\": {\n"
        + "    \"track\": {\n"
        + "      \"bar\": {\n"
        + "        \"enabled\": true,\n"
        + "        \"integrations\": {\n"
        + "          \"All\": false\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}");

    new PayloadOperation(trackPayload).run(mockIntegration, projectSettings);

    verify(mockIntegration, times(2)).key();
    verifyNoMoreInteractions(mockIntegration);
  }

  @Test public void payloadEnabledCorrectly() throws IOException {
    // This test should be done with JUnit params. Couldn't get it to work
    // http://pastebin.com/W61q1H3J.
    List<Pair<Options, Boolean>> params = new ArrayList<>();
    params.add(new Pair<>(new Options(), true));

    // Respect "All" capital case
    params.add(new Pair<>(new Options().setIntegration("All", false), false));
    params.add(new Pair<>(new Options().setIntegration("All", true), true));

    // Ignore "all" under case
    params.add(new Pair<>(new Options().setIntegration("all", false), true));
    params.add(new Pair<>(new Options().setIntegration("all", true), true));

    // respect options for "foo" integration
    params.add(new Pair<>(new Options().setIntegration("foo", true), true));
    params.add(new Pair<>(new Options().setIntegration("foo", false), false));

    // ignore values for other integrations
    params.add(new Pair<>(new Options().setIntegration("bar", true), true));
    params.add(new Pair<>(new Options().setIntegration("bar", false), true));

    for (Pair<Options, Boolean> param : params) {
      AnalyticsContext analyticsContext = createContext(new Traits());
      BasePayload payload = new AliasPayload(analyticsContext, param.first, "foo");
      boolean enabled =
          PayloadOperation.isIntegrationEnabled(payload.integrations(), mockIntegration);
      assertThat(enabled).overridingErrorMessage("Expected %s for integrations %s", param.second,
          param.first.integrations()).isEqualTo(param.second);
    }
  }

  @Test public void initializesIntegrations() throws Exception {
    /*
    final AbstractIntegration mockIntegration = mock(AbstractIntegration.class);
    when(mockIntegration.key()).thenReturn("Foo");
    integrationManager.initialized = false;
    integrationManager.integrations.clear();
    integrationManager.integrations.add(mockIntegration);

    ValueMap fooMap =
        new ValueMap().putValue("trackNamedPages", true).putValue("trackAllPages", false);

    integrationManager.performInitializeIntegrations(ProjectSettings //
        .create(new ValueMap().putValue("Foo", fooMap), System.currentTimeMillis()));

    verify(mockIntegration).initialize(context, new ValueMap(fooMap), true);

    assertThat(integrationManager.initialized).isTrue();

    // exercise a bug where we added an integration twice, once on load and once on initialize
    assertThat(integrationManager.integrations).containsExactly(mockIntegration);
    */
  }

  @Test public void fetchSettingsSubmitsToExecutor() throws Exception {
    integrationManager.performFetchSettings();

    verify(networkExecutor).submit(any(Callable.class));
  }

  @Test public void forwardsCorrectly() {
    integrationManager.initialized = true;
    integrationManager.integrations.add(mockIntegration);
    integrationManager.integrations.add(mockIntegration);
    integrationManager.integrations.add(mockIntegration);

    integrationManager.performOperation(new IntegrationOperation() {
      @Override public void run(AbstractIntegration integration, ProjectSettings projectSettings) {
        integration.alias(mock(AliasPayload.class));
      }

      @Override public String id() {
        return null;
      }
    });
    verify(mockIntegration, times(3)).alias(any(AliasPayload.class));

    integrationManager.performOperation(new IntegrationOperation() {
      @Override public void run(AbstractIntegration integration, ProjectSettings projectSettings) {
        integration.flush();
      }

      @Override public String id() {
        return null;
      }
    });
    verify(mockIntegration, times(3)).flush();
  }
}

