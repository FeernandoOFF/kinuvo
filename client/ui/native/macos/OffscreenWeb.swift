import AppKit
import WebKit

private final class SurfaceWindow: NSWindow {
    override var canBecomeKey: Bool { true }
}

private final class OffscreenWeb: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    let lock = NSLock()
    var messages = [String]()
    var frame: UnsafeMutableRawPointer?
    var web: WKWebView?
    var window: NSWindow?
    var pending = false
    var capturing = false
    var closed = false
    var lastCapture = Date.distantPast
    let created = Date()
    var count = 0
    var totalMs = 0.0
    var maxMs = 0.0
    var firstMs = 0.0

    func start() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.add(self, name: "kinuvoNative")
        config.userContentController.add(self, name: "kinuvoDirty")
        config.userContentController.addUserScript(WKUserScript(source: """
          (()=>{
            let queued=false;
            const dirty=()=>{if(queued)return;queued=true;setTimeout(()=>{queued=false;webkit.messageHandlers.kinuvoDirty.postMessage('dirty')},33)};
            new MutationObserver(dirty).observe(document,{subtree:true,childList:true,attributes:true,characterData:true});
            ['input','change','pointermove','scroll','focusin','focusout','load'].forEach(e=>addEventListener(e,dirty,true));
            const raf=window.requestAnimationFrame.bind(window);window.requestAnimationFrame=callback=>raf(time=>{callback(time);dirty()});
            let animating=false;
            const animate=()=>{dirty();if(document.getAnimations().some(a=>a.playState==='running'))requestAnimationFrame(animate);else animating=false};
            addEventListener('animationstart',()=>{if(!animating){animating=true;animate()}},true);
            addEventListener('transitionrun',()=>{if(!animating){animating=true;animate()}},true);
            setInterval(()=>{if(document.activeElement?.matches('input,textarea,[contenteditable]'))dirty()},500);
          })();
          """, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        let view = WKWebView(frame: NSRect(x: 0, y: 0, width: 700, height: 500), configuration: config)
        // Transparent, so snapshots blend with the chat behind them.
        view.setValue(false, forKey: "drawsBackground")
        let host = SurfaceWindow(contentRect: view.frame.offsetBy(dx: -10000, dy: -10000), styleMask: .borderless, backing: .buffered, defer: false)
        host.isOpaque = false
        host.backgroundColor = .clear
        host.isReleasedWhenClosed = false
        host.ignoresMouseEvents = true
        host.contentView = view
        host.orderFrontRegardless()
        view.navigationDelegate = self
        web = view
        window = host
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        if message.name == "kinuvoDirty" { dirty(); return }
        guard message.frameInfo.isMainFrame, let text = message.body as? String else { return }
        guard text.utf8.count <= 512 * 1024 else { return }
        lock.lock(); if messages.count < 256 { messages.append(text) }; lock.unlock()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { dirty() }

    func dirty() {
        guard !closed else { return }
        pending = true
        guard !capturing else { return }
        capturing = true
        let delay = max(0, 1.0 / 30 - Date().timeIntervalSince(lastCapture))
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [self] in snapshot() }
    }

    func snapshot() {
        guard !closed, let web else { capturing = false; return }
        pending = false
        lastCapture = Date()
        let config = WKSnapshotConfiguration()
        config.snapshotWidth = NSNumber(value: web.bounds.width * 2)
        config.afterScreenUpdates = true
        let started = Date()
        web.takeSnapshot(with: config) { [self] image, error in
            let ms = Date().timeIntervalSince(started) * 1000
            count += 1; totalMs += ms; maxMs = max(maxMs, ms)
            if firstMs == 0 { firstMs = Date().timeIntervalSince(created) * 1000 }
            if !closed, let image, let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) {
                let width = cg.width, height = cg.height
                let bytes = width * height * 4
                if let buffer = calloc(bytes + 8, 1) {
                    buffer.storeBytes(of: Int32(width), as: Int32.self)
                    buffer.advanced(by: 4).storeBytes(of: Int32(height), as: Int32.self)
                    if let context = CGContext(data: buffer.advanced(by: 8), width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue) {
                        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
                        lock.lock(); if let frame { free(frame) }; frame = buffer; lock.unlock()
                    } else { free(buffer) }
                }
            }
            capturing = false
            if pending { dirty() }
        }
    }

    func send(_ command: [String: Any]) {
        guard !closed, let web, let window, let op = command["op"] as? String else { return }
        switch op {
        case "stats":
            let stats: [String: Any] = ["nativeStats": true, "count": count, "meanMs": totalMs / Double(max(1, count)), "maxMs": maxMs, "firstMs": firstMs]
            if let data = try? JSONSerialization.data(withJSONObject: stats) {
                lock.lock(); messages.append(String(decoding: data, as: UTF8.self)); lock.unlock()
            }
            return
        case "load": web.loadHTMLString(command["html"] as? String ?? "", baseURL: nil)
        case "eval": web.evaluateJavaScript(command["js"] as? String ?? "") { [weak self] _, _ in self?.dirty() }
        case "size":
            let width = max(1, min(4096, command["width"] as? Double ?? 700))
            let height = max(1, min(2048, command["height"] as? Double ?? 500))
            window.setContentSize(NSSize(width: width, height: height))
            web.setFrameSize(NSSize(width: width, height: height))
        case "focus":
            if command["focused"] as? Bool == true { window.makeFirstResponder(web) }
            else { window.makeFirstResponder(nil); window.resignKey() }
        case "text": (web as? NSTextInputClient)?.insertText(command["text"] as? String ?? "", replacementRange: NSRange(location: NSNotFound, length: 0))
        case "key":
            let flags = NSEvent.ModifierFlags(rawValue: UInt(command["modifiers"] as? Int ?? 0))
            let text = command["text"] as? String ?? ""
            let up = command["up"] as? Bool == true
            if let event = NSEvent.keyEvent(with: up ? .keyUp : .keyDown, location: .zero, modifierFlags: flags, timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, characters: text, charactersIgnoringModifiers: text, isARepeat: false, keyCode: UInt16(command["code"] as? Int ?? 0)) {
                if up { web.keyUp(with: event) }
                else if !web.performKeyEquivalent(with: event) { web.keyDown(with: event) }
            }
        case "mouse":
            let x = command["x"] as? Double ?? 0, y = command["y"] as? Double ?? 0
            let kind = command["kind"] as? String ?? "move"
            let secondary = command["secondary"] as? Bool == true
            let type: NSEvent.EventType = switch kind {
            case "down": secondary ? .rightMouseDown : .leftMouseDown
            case "up": secondary ? .rightMouseUp : .leftMouseUp
            case "drag": secondary ? .rightMouseDragged : .leftMouseDragged
            default: .mouseMoved
            }
            if let event = NSEvent.mouseEvent(with: type, location: NSPoint(x: x, y: web.bounds.height - y), modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(command["modifiers"] as? Int ?? 0)), timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: command["clicks"] as? Int ?? 1, pressure: 1) {
                let target = web.hitTest(NSPoint(x: x, y: y)) ?? web
                switch type {
                case .leftMouseDown: target.mouseDown(with: event)
                case .leftMouseUp: target.mouseUp(with: event)
                case .leftMouseDragged: target.mouseDragged(with: event)
                case .rightMouseDown: target.rightMouseDown(with: event)
                case .rightMouseUp: target.rightMouseUp(with: event)
                case .rightMouseDragged: target.rightMouseDragged(with: event)
                default: target.mouseMoved(with: event)
                }
            }
        case "scroll":
            if let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: Int32(command["dy"] as? Double ?? 0), wheel2: Int32(command["dx"] as? Double ?? 0), wheel3: 0), let native = NSEvent(cgEvent: event) { web.scrollWheel(with: native) }
        default: break
        }
        dirty()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) { [weak self] in self?.dirty() }
    }

    func close() {
        closed = true
        web?.stopLoading()
        web?.configuration.userContentController.removeAllScriptMessageHandlers()
        web?.navigationDelegate = nil
        window?.close()
        web = nil; window = nil
        lock.lock(); if let frame { free(frame) }; frame = nil; messages.removeAll(); lock.unlock()
    }
}

@_cdecl("kinuvo_web_create")
public func createWeb() -> UnsafeMutableRawPointer {
    let host = OffscreenWeb()
    DispatchQueue.main.async { host.start() }
    return Unmanaged.passRetained(host).toOpaque()
}

@_cdecl("kinuvo_web_send")
public func sendWeb(_ handle: UnsafeMutableRawPointer, _ json: UnsafePointer<CChar>) {
    let host = Unmanaged<OffscreenWeb>.fromOpaque(handle).takeUnretainedValue()
    guard let data = String(cString: json).data(using: .utf8), let command = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
    DispatchQueue.main.async { host.send(command) }
}

@_cdecl("kinuvo_web_poll")
public func pollWeb(_ handle: UnsafeMutableRawPointer) -> UnsafeMutablePointer<CChar>? {
    let host = Unmanaged<OffscreenWeb>.fromOpaque(handle).takeUnretainedValue()
    host.lock.lock(); defer { host.lock.unlock() }
    return host.messages.isEmpty ? nil : strdup(host.messages.removeFirst())
}

@_cdecl("kinuvo_web_frame")
public func frameWeb(_ handle: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer? {
    let host = Unmanaged<OffscreenWeb>.fromOpaque(handle).takeUnretainedValue()
    host.lock.lock(); defer { host.lock.unlock() }
    let frame = host.frame; host.frame = nil; return frame
}

@_cdecl("kinuvo_web_free")
public func freeWeb(_ pointer: UnsafeMutableRawPointer) { free(pointer) }

@_cdecl("kinuvo_web_close")
public func closeWeb(_ handle: UnsafeMutableRawPointer) {
    let host = Unmanaged<OffscreenWeb>.fromOpaque(handle).takeRetainedValue()
    DispatchQueue.main.async { host.close() }
}
