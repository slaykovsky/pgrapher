var getJSON = function(url, cb) {
    var request = new XMLHttpRequest();
    request.open("GET", url, true);
    request.responseType = "json";
    request.onload = function() {
        var status = request.status;
        if (status == 200) {
            callback(null, request.response);
        } else {
            callback(status);
        }
    };
    request.send();
};

getJSON("http://pgrapher.slaykovsky.com/api/machines", function(err, data) {
    if (err != null) {
        alert(err);
    }  else {
        console.log(data);

        var coolData = {};

        for (var i in data) {
            var test = data[i]["test"];
            var threads = data[i]["threads"].toString();
            var result = data[i]["average_result"];
            var hostname = data[i]["hostname"];

            coolData[test] = coolData[test] || {};
            coolData[test][threads] = coolData[test][threads] || [];
            coolData[test][threads].push({"hostname": hostname, "result": result});
        }

        console.log(coolData);

    }
});