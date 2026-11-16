package kinuvo.app.connection

import kinuvo.client.KinuvoClient

/** State tied to one Server: [ConnectedServer] attaches it on activate and detaches it on close. */
interface ServerBound {
    fun attach(client: KinuvoClient) {}
    fun detach()
}
