var mpvrx = {};
var mpvrxCurlSequence = 0;
var mpvrxCurlRequestProperty = "user-data/mpvrx/curl/request";

function mpvrxCurlSanitize(value) {
  value = String(value || "script").replace(/[^A-Za-z0-9_]/g, "_");
  return value.length ? value : "script";
}

function mpvrxCurlNextId() {
  mpvrxCurlSequence += 1;
  return [
    "curl",
    mpvrxCurlSanitize(mp.get_script_name()),
    String(mp.get_time_ms ? mp.get_time_ms() : Math.floor(mp.get_time() * 1000)),
    String(mpvrxCurlSequence)
  ].join("_");
}

function mpvrxCurlCloneHeaders(headers) {
  var output = {};
  var key;
  headers = headers || {};
  for (key in headers) {
    if (Object.prototype.hasOwnProperty.call(headers, key)) {
      output[key] = headers[key];
    }
  }
  return output;
}

function mpvrxCurlApplyJsonBody(params, request) {
  if (params.json === undefined) {
    return true;
  }

  request.body = JSON.stringify(params.json);
  request.headers = mpvrxCurlCloneHeaders(request.headers);
  if (request.headers["Content-Type"] === undefined && request.headers["content-type"] === undefined) {
    request.headers["Content-Type"] = "application/json";
  }
  return true;
}

mpvrx.curl = function(params, callback) {
  if (!params || !params.url) {
    mp.msg.warn("mpvrx.curl: url is required");
    return null;
  }

  var id = mpvrxCurlNextId();
  var messageName = "mpvrx_curl_resp_" + id;

  mp.register_script_message(messageName, function(responseJson) {
    mp.unregister_script_message(messageName);

    var response;
    try {
      response = JSON.parse(responseJson);
    } catch (error) {
      response = {
        id: id,
        status: 0,
        body: "",
        headers: {},
        ok: false,
        error: "Failed to parse curl response: " + error.message
      };
    }

    if (response.error !== null && response.error !== undefined) {
      mp.msg.warn("mpvrx.curl error: " + response.error);
    }

    if (callback) {
      callback(response);
    }
  });

  var request = {
    id: id,
    target: mp.get_script_name(),
    msg: messageName,
    url: params.url,
    method: params.method || "GET",
    headers: params.headers || {},
    body: params.body || "",
    timeout: params.timeout || 30,
    insecure: params.insecure === true,
    output_file: params.output_file || "",
    upload_file: params.upload_file || "",
    base64_response: params.base64_response === true,
    base64_body: params.base64_body === true,
    follow_redirects: params.follow_redirects !== false
  };

  if (!mpvrxCurlApplyJsonBody(params, request)) {
    return null;
  }

  mp.set_property(mpvrxCurlRequestProperty, JSON.stringify(request));
  return id;
};

mpvrx.get = function(url, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  return mpvrx.curl({ url: url, method: "GET", headers: headers || {} }, callback);
};

mpvrx.head = function(url, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  return mpvrx.curl({ url: url, method: "HEAD", headers: headers || {} }, callback);
};

mpvrx.post = function(url, body, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  if (typeof body === "function") {
    callback = body;
    body = "";
    headers = {};
  }
  return mpvrx.curl({ url: url, method: "POST", body: body || "", headers: headers || {} }, callback);
};

mpvrx.put = function(url, body, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  if (typeof body === "function") {
    callback = body;
    body = "";
    headers = {};
  }
  return mpvrx.curl({ url: url, method: "PUT", body: body || "", headers: headers || {} }, callback);
};

mpvrx.patch = function(url, body, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  if (typeof body === "function") {
    callback = body;
    body = "";
    headers = {};
  }
  return mpvrx.curl({ url: url, method: "PATCH", body: body || "", headers: headers || {} }, callback);
};

mpvrx.del = function(url, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  return mpvrx.curl({ url: url, method: "DELETE", headers: headers || {} }, callback);
};

mpvrx["delete"] = mpvrx.del;

mpvrx.download = function(url, outputFile, headers, callback) {
  if (typeof headers === "function") {
    callback = headers;
    headers = {};
  }
  return mpvrx.curl({
    url: url,
    method: "GET",
    headers: headers || {},
    output_file: outputFile
  }, callback);
};

if (typeof exports !== "undefined") {
  for (var mpvrxCurlKey in mpvrx) {
    if (Object.prototype.hasOwnProperty.call(mpvrx, mpvrxCurlKey)) {
      exports[mpvrxCurlKey] = mpvrx[mpvrxCurlKey];
    }
  }
}

this.mpvrx = mpvrx;
