package kinuvo.app.agent

import kinuvo.app.settings.*
import kinuvo.protocol.knowledge.*
import kinuvo.protocol.service.*
import kinuvo.protocol.settings.*
import kotlin.test.*

class ProviderSettingsFormTest {
    private val schema = SettingsSchema(listOf(
        SettingsField("password", "Password", sensitive = true),
        SettingsField("url", "URL", defaultValue = "https://example.org"),
        SettingsField("topic", "Topic", sensitive = true, randomPrefix = "kinuvo-", link = SettingsFieldLink("url")),
    ))
    private val setup = ServiceSetup(ServiceKind.Notifications,
        ServiceSettings("first", mapOf("password" to KnowledgeSettings.SECRET, "topic" to KnowledgeSettings.SECRET)),
        listOf(SettingsProvider("first", schema = schema), SettingsProvider("second", schema = schema)),
        setOf("password", "topic"))

    @Test fun savedCredentialsAreKeptWithoutBecomingInputValues() {
        val form = ProviderSettingsForm(setup)
        assertEquals("", form.value("password"))
        assertTrue(form.configured("password"))
        assertEquals(SecretUpdate.Keep, form.saveParams(ServiceKind.Notifications).secretUpdates["password"])
        assertFalse("password" in form.saveParams(ServiceKind.Notifications).settings.options)
        assertEquals(SecretUpdate.Replace("new"), form.change("password", "new").saveParams(ServiceKind.Notifications).secretUpdates["password"])
        assertEquals(SecretUpdate.Remove, form.removeSecret("password").testParams(ServiceKind.Notifications).secretUpdates["password"])
        assertEquals(SecretUpdate.Keep, form.removeSecret("password").keepSecret("password").saveParams(ServiceKind.Notifications).secretUpdates["password"])
    }

    @Test fun switchingProvidersKeepsIndependentDraftsAndDefaults() {
        val first = ProviderSettingsForm(setup).change("password", "first-secret").change("url", "https://first.example")
        val second = first.select("second")
        assertFalse(second.configured("password"))
        assertEquals("", second.value("password"))
        assertEquals("", second.value("url"))
        assertEquals("https://example.org", second.effectiveValue("url"))
        assertEquals("first-secret", second.change("password", "second-secret").select("first").value("password"))
        assertEquals(ServiceSettings(), second.select(null).saveParams(ServiceKind.Notifications).settings)
    }

    @Test fun genericGeneratorsAndLinksDoNotExposeSavedSecretsOrResolveReferences() {
        val form = ProviderSettingsForm(setup)
        assertNull(form.link("topic"))
        val generated = form.generate("topic")
        assertTrue(generated.value("topic").startsWith("kinuvo-"))
        assertEquals("https://example.org/${generated.value("topic")}", generated.link("topic"))
        assertNull(generated.change("url", "javascript:alert(1)").link("topic"))
        assertNull(form.change("topic", "\${TOPIC}").link("topic"))
        assertEquals("https://example.org/a%2Fb", form.change("topic", "a/b").link("topic"))
    }

    @Test fun unsupportedSchemasAndMissingMetadataCannotOverwriteSettings() {
        val future = setup.copy(providers = listOf(SettingsProvider("first", schema = schema.copy(version = 2))))
        for (s in listOf(future, setup.copy(providers = emptyList()))) {
            val form = ProviderSettingsForm(s)
            assertFalse(form.editable)
            assertFailsWith<IllegalStateException> { form.saveParams(ServiceKind.Notifications) }
        }
    }

    @Test fun unsupportedFieldTypesOnlyDisableTheirOwnProvider() {
        for (version in listOf(1, 2)) {
            val future = SettingsProvider("first", schema = SettingsSchema(listOf(SettingsField("timeout", "Timeout", SettingsFieldType("Duration"))), version))
            val form = ProviderSettingsForm(setup.copy(providers = listOf(future, setup.providers.last())))
            assertFalse(form.editable)
            assertFailsWith<IllegalStateException> { form.saveParams(ServiceKind.Notifications) }
            assertTrue(form.select("second").editable)
        }
    }

    @Test fun implicitDefaultsAreDisplayedWithoutBeingSavedAndExplicitDefaultsAreRetained() {
        val form = ProviderSettingsForm(setup)
        assertEquals("", form.value("url"))
        assertEquals("https://example.org", form.effectiveValue("url"))
        assertFalse("url" in form.saveParams(ServiceKind.Notifications).settings.options)
        assertFalse("url" in form.testParams(ServiceKind.Notifications).settings!!.options)
        val explicit = form.change("url", "https://example.org")
        assertEquals("https://example.org", explicit.saveParams(ServiceKind.Notifications).settings.options["url"])
        assertFalse("url" in explicit.change("url", "").saveParams(ServiceKind.Notifications).settings.options)
        val saved = ProviderSettingsForm(setup.copy(settings = setup.settings.copy(options = setup.settings.options + ("url" to "https://example.org"))))
        assertEquals("https://example.org", saved.saveParams(ServiceKind.Notifications).settings.options["url"])
    }

    @Test fun visibleTopicsCanBeOpenedAfterReopeningSettings() {
        val visibleSchema = schema.copy(fields = schema.fields.map { if (it.key == "topic") it.copy(sensitive = false) else it })
        val reopened = ProviderSettingsForm(setup.copy(settings = setup.settings.copy(options = setup.settings.options + ("topic" to "kinuvo-saved")),
            providers = listOf(SettingsProvider("first", schema = visibleSchema))))
        assertEquals("kinuvo-saved", reopened.value("topic"))
        assertEquals("https://example.org/kinuvo-saved", reopened.link("topic"))
        val generated = reopened.generate("topic")
        assertEquals(generated.value("topic"), generated.saveParams(ServiceKind.Notifications).settings.options["topic"])
        assertFalse("topic" in generated.saveParams(ServiceKind.Notifications).secretUpdates)
    }
}
