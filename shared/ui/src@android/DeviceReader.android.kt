package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts as Contacts
import android.provider.ContactsContract.PhoneLookup as PhoneLookup
import android.provider.ContactsContract.CommonDataKinds.Email as Email
import android.provider.ContactsContract.Data as ContactData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kinuvo.protocol.*
import kotlinx.serialization.json.*
import java.util.concurrent.Executors

internal class AndroidContactsReader(private val context: Context) : DeviceReader {
    override val kinds = listOf("Contacts")
    private val executor = Executors.newSingleThreadExecutor()
    override fun authorize(kind: String, completion: (String?) -> Unit) = completion("Grant Contacts access in Kinuvo settings")

    override fun read(requestJson: String, completion: (String) -> Unit) {
        executor.execute {
            val result = try {
                val request = ProtocolJson.decodeFromString(DeviceToolRequest.serializer(), requestJson)
                when {
                    request.kind != DeviceToolKind.Contacts -> DeviceToolResult(DeviceToolStatus.Unsupported)
                    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED -> DeviceToolResult(DeviceToolStatus.Denied, message = "Contacts access is denied. Enable it in Android Settings.")
                    else -> lookup(request)
                }
            } catch (_: Exception) { DeviceToolResult(DeviceToolStatus.Unavailable, message = "Contacts could not be read") }
            completion(ProtocolJson.encodeToString(DeviceToolResult.serializer(), result))
        }
    }

    private fun lookup(request: DeviceToolRequest): DeviceToolResult {
        val query = requireNotNull(request.query).trim()
        require(query.isNotEmpty() && query.length <= 200)
        val limit = request.limit.coerceIn(1, 20)
        val ids = linkedMapOf<String, String>()
        val uri: Uri
        val idColumn: String
        val nameColumn: String
        when {
            '@' in query -> { uri = Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(query)); idColumn = Email.CONTACT_ID; nameColumn = Email.DISPLAY_NAME }
            query.any(Char::isDigit) && query.all { it.isDigit() || it in "+()- ." } -> { uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(query)); idColumn = PhoneLookup._ID; nameColumn = PhoneLookup.DISPLAY_NAME }
            else -> { uri = Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(query)); idColumn = Contacts._ID; nameColumn = Contacts.DISPLAY_NAME_PRIMARY }
        }
        context.contentResolver.query(uri, arrayOf(idColumn, nameColumn), null, null, "$nameColumn ASC")?.use { cursor ->
            while (ids.size <= limit && cursor.moveToNext()) ids[cursor.getString(0)] = cursor.getString(1).orEmpty()
        } ?: error("Contacts provider is unavailable")
        val matches = ids.entries.take(limit).map { (id, name) ->
            val phones = mutableSetOf<String>()
            val emails = mutableSetOf<String>()
            context.contentResolver.query(ContactData.CONTENT_URI, arrayOf(ContactData.MIMETYPE, ContactData.DATA1), "${ContactData.CONTACT_ID} = ? AND ${ContactData.MIMETYPE} IN (?, ?)", arrayOf(id, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val value = cursor.getString(1) ?: continue
                    if (cursor.getString(0) == Email.CONTENT_ITEM_TYPE) { if (emails.size < 20) emails += value.take(500) }
                    else if (phones.size < 20) phones += value.take(100)
                }
            }
            buildJsonObject {
                put("name", name.take(500))
                put("phones", JsonArray(phones.map(::JsonPrimitive)))
                put("emails", JsonArray(emails.map(::JsonPrimitive)))
            }
        }
        return DeviceToolResult(DeviceToolStatus.Ready, buildJsonObject {
            put("source", "Android Contacts")
            put("contacts", JsonArray(matches))
            put("truncated", ids.size > limit)
        })
    }
}

@Composable
internal actual fun rememberDeviceAuthorization(reader: DeviceReader): (DeviceToolKind, (String?) -> Unit) -> Unit {
    val context = LocalContext.current
    var completion by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        completion?.invoke(if (granted) null else "Contacts access was denied. You can grant it in Android Settings.")
        completion = null
    }
    // Computer use is turned on in Android's Accessibility settings; this waits for the user to come back with it on.
    var computer by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    LaunchedEffect(computer) {
        val waiting = computer ?: return@LaunchedEffect
        val until = System.currentTimeMillis() + 120_000
        while (ComputerService.instance == null && System.currentTimeMillis() < until) kotlinx.coroutines.delay(500)
        waiting(if (ComputerService.instance != null) null else "Turn on Kinuvo computer use in Android's Accessibility settings, then allow it again.")
        computer = null
    }
    return { kind, callback ->
        when {
            kind == DeviceToolKind.Computer -> if (ComputerService.instance != null) callback(null) else {
                computer = callback
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            kind != DeviceToolKind.Contacts -> callback("This Device does not support that tool")
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED -> callback(null)
            else -> { completion = callback; launcher.launch(Manifest.permission.READ_CONTACTS) }
        }
    }
}
