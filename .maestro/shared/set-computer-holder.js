var base = 'http://127.0.0.1:' + WIREMOCK_PORT;
var control = { generation: HOLDER ? 1 : 2 };
if (HOLDER) control.holder = HOLDER;
var screen = {
    ref: { source: 'computer', id: 'phone' }, title: 'Pixel', status: 'Ready', active: false, control: control,
    capabilities: { delivery: 'Continuous', inputs: ['tap', 'type', 'key', 'scroll'], keys: ['Back', 'Home', 'Recents'] }
};
var data = '{"type":"res","id":"{{jsonPath message.body \'$.id\'}}","ok":true,"payload":' + JSON.stringify([screen]) + '}';
var response = http.post(base + '/__admin/message-mappings', {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        name: 'mobile/computers-' + (HOLDER ? 'held' : 'released'),
        priority: Number(PRIORITY),
        trigger: {
            type: 'message',
            channel: { type: 'websocket', initiatingRequestPattern: { url: '/ws' } },
            message: { body: { matchesJsonPath: { expression: '$.method', equalTo: 'screens.list' } } }
        },
        actions: [{ type: 'send', channelTarget: { type: 'originating' }, message: { body: { data: data } } }]
    })
});
if (response.status !== 201 && response.status !== 200) throw new Error('WireMock stub failed: ' + response.status + ' ' + response.body);
