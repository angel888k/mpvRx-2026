local utils = require("mp.utils")

local mpvrx = {}
local sequence = 0
local request_property = "user-data/mpvrx/curl/request"

local function sanitize_message_part(value)
  value = tostring(value or "script")
  value = value:gsub("[^%w_]", "_")
  if value == "" then
    return "script"
  end
  return value
end

local function next_request_id()
  sequence = sequence + 1
  local millis = math.floor((mp.get_time() or 0) * 1000)
  return string.format(
    "curl_%s_%d_%d",
    sanitize_message_part(mp.get_script_name()),
    millis,
    sequence
  )
end

local function encode_json(value)
  local encoded, error_message = utils.format_json(value)
  if not encoded then
    mp.msg.warn("mpvrx.curl: failed to encode request: " .. tostring(error_message))
    return nil
  end
  return encoded
end

local function decode_json(value)
  local decoded, error_message = utils.parse_json(value)
  if decoded == nil and error_message then
    return nil, error_message
  end
  return decoded, nil
end

local function normalize_headers(headers)
  return headers or {}
end

local function ensure_json_body(params, request)
  if params.json == nil then
    return true
  end

  local body = encode_json(params.json)
  if not body then
    return false
  end

  request.body = body
  request.headers = normalize_headers(request.headers)
  if request.headers["Content-Type"] == nil and request.headers["content-type"] == nil then
    request.headers["Content-Type"] = "application/json"
  end
  return true
end

mpvrx._json_encode = encode_json

function mpvrx._json_decode(value)
  local decoded, error_message = decode_json(value)
  if error_message then
    error(error_message)
  end
  return decoded
end

function mpvrx.curl(params, callback)
  if type(params) ~= "table" or not params.url then
    mp.msg.warn("mpvrx.curl: url is required")
    return nil
  end

  local id = next_request_id()
  local message_name = "mpvrx_curl_resp_" .. id

  mp.register_script_message(message_name, function(response_json)
    mp.unregister_script_message(message_name)

    local response, parse_error = decode_json(response_json)
    if not response then
      response = {
        id = id,
        status = 0,
        body = "",
        headers = {},
        ok = false,
        error = "Failed to parse curl response: " .. tostring(parse_error),
      }
    elseif response.error ~= nil then
      mp.msg.warn("mpvrx.curl error: " .. tostring(response.error))
    end

    if callback then
      callback(response)
    end
  end)

  local request = {
    id = id,
    target = mp.get_script_name(),
    msg = message_name,
    url = params.url,
    method = params.method or "GET",
    headers = normalize_headers(params.headers),
    body = params.body or "",
    timeout = params.timeout or 30,
    insecure = params.insecure == true,
    output_file = params.output_file or "",
    upload_file = params.upload_file or "",
    base64_response = params.base64_response == true,
    base64_body = params.base64_body == true,
    follow_redirects = params.follow_redirects ~= false,
  }

  if not ensure_json_body(params, request) then
    return nil
  end

  local payload = encode_json(request)
  if not payload then
    return nil
  end

  mp.set_property(request_property, payload)
  return id
end

function mpvrx.get(url, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  return mpvrx.curl({ url = url, method = "GET", headers = headers or {} }, callback)
end

function mpvrx.head(url, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  return mpvrx.curl({ url = url, method = "HEAD", headers = headers or {} }, callback)
end

function mpvrx.post(url, body, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  if type(body) == "function" then
    callback = body
    body = ""
    headers = {}
  end
  return mpvrx.curl({ url = url, method = "POST", body = body or "", headers = headers or {} }, callback)
end

function mpvrx.put(url, body, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  if type(body) == "function" then
    callback = body
    body = ""
    headers = {}
  end
  return mpvrx.curl({ url = url, method = "PUT", body = body or "", headers = headers or {} }, callback)
end

function mpvrx.patch(url, body, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  if type(body) == "function" then
    callback = body
    body = ""
    headers = {}
  end
  return mpvrx.curl({ url = url, method = "PATCH", body = body or "", headers = headers or {} }, callback)
end

function mpvrx.delete(url, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  return mpvrx.curl({ url = url, method = "DELETE", headers = headers or {} }, callback)
end

mpvrx.del = mpvrx.delete

function mpvrx.download(url, output_file, headers, callback)
  if type(headers) == "function" then
    callback = headers
    headers = {}
  end
  return mpvrx.curl({
    url = url,
    method = "GET",
    headers = headers or {},
    output_file = output_file,
  }, callback)
end

return mpvrx
