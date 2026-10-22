var response = http.get('http://127.0.0.1:' + WIREMOCK_PORT + '/__admin/messages');
if (response.status !== 200) throw new Error('Cannot read message journal: ' + response.body);
var events = json(response.body).messageServeEvents.filter(function (event) {
    return event.eventType === 'received';
}).sort(function (a, b) { return a.timestamp.localeCompare(b.timestamp); });
var watches = events.map(function (event) { return json(event.message.body); }).filter(function (message) {
    return message.method === 'computer.watch' && message.params.computerId === 'phone';
}).map(function (message) { return message.params.watch; });
if (JSON.stringify(watches) !== EXPECTED) throw new Error('Expected watches ' + EXPECTED + ', received ' + JSON.stringify(watches));
var releases = events.map(function (event) { return json(event.message.body); }).filter(function (message) {
    return message.method === 'computer.takeover' && message.params.take === false;
});
if (releases.length !== 0) throw new Error('Closing a preview implicitly handed the Computer back');
