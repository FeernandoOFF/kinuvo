package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.browserRoutes() {
    handle(Api.BrowserPresentation) { server.browser.presentation(it) }
    handle(Api.SaveBrowserPresentation) { server.browser.savePresentation(it) }
    handle(Api.BrowserTabs) { server.browser.tabs(it) }
    handle(Api.BrowserCommand) { server.browser.command(it) }
}
