package com.example.offgrid;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void meshServiceIsRegisteredWithItsRuntimeClass() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ServiceInfo service =
                context.getPackageManager()
                        .getServiceInfo(new ComponentName(context, MeshService.class), 0);
        assertEquals(MeshService.class.getName(), service.name);
        assertTrue(service.enabled);
        assertFalse(service.exported);
    }

    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.mittalsukhansh.offgrid.debug", appContext.getPackageName());
    }

    @Test
    public void fileProviderAuthorityTracksTheInstalledApplicationId() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.pm.ProviderInfo provider =
                context.getPackageManager()
                        .getProviderInfo(
                                new ComponentName(
                                        context, androidx.core.content.FileProvider.class),
                                0);
        assertEquals(context.getPackageName() + ".files", provider.authority);
        assertFalse(provider.exported);
        assertTrue(provider.grantUriPermissions);
    }
}
