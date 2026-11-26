import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class WebRtcProbe {
    static void run() throws Exception {
        var audio = new HeadlessAudioDeviceModule();
        PeerConnectionFactory factory = null;
        RTCPeerConnection peer = null;
        RTCDataChannel channel = null;
        try {
            factory = new PeerConnectionFactory(audio);
            peer = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                @Override public void onIceCandidate(RTCIceCandidate candidate) {}
            });
            if (peer == null || peer.getConfiguration().iceServers == null) throw new AssertionError("Peer configuration missing");
            channel = peer.createDataChannel("native-probe", new RTCDataChannelInit());
            if (channel == null || !channel.getLabel().equals("native-probe")) throw new AssertionError("Data channel missing");
            var offer = new CompletableFuture<RTCSessionDescription>();
            peer.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription description) { offer.complete(description); }
                @Override public void onFailure(String error) { offer.completeExceptionally(new IllegalStateException(error)); }
            });
            var description = offer.get(15, TimeUnit.SECONDS);
            if (!description.sdp.contains("m=application")) throw new AssertionError("Data channel offer missing");
            System.out.println("WEBRTC_READY configuration/data-channel/offer");
        } finally {
            if (channel != null) { channel.close(); channel.dispose(); }
            if (peer != null) peer.close();
            if (factory != null) factory.dispose();
            audio.dispose();
        }
    }
}
