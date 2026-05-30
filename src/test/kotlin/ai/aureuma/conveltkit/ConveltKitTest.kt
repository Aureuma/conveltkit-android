package ai.aureuma.conveltkit

import org.junit.Assert.assertEquals
import org.junit.Test

class ConveltKitTest {
    @Test
    fun sdkIdentityIsStable() {
        assertEquals("ConveltKit", ConveltKit.sdkName)
        assertEquals(ConveltKitVersion.value, ConveltKit.sdkVersion)
    }
}
