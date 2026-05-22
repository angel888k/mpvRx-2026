# mpvRx Curl – HTTP Requests from Lua/JS Scripts

Make HTTP requests directly in your mpv scripts. Uses OkHttp under the hood.

## How It Works

mpvRx installs two helpers into mpv's config folder before the player starts:
- `~~/scripts/mpvrx_curl.lua` – loaded by Lua scripts with `dofile()`
- `mpvrx_curl` JS module – loaded by JS scripts with `require("mpvrx_curl")`

Your scripts call functions from these helpers to make GET, HEAD, POST, PUT, PATCH, DELETE, upload, and download requests. The request is routed to Kotlin/OkHttp, executed, and the response is sent back asynchronously to the calling script.

## Usage (Lua Scripts)

In your `.lua` script, load the helper and make requests:

```lua
local mpvrx = dofile("~~/scripts/mpvrx_curl.lua")

-- GET request
mpvrx.get("https://api.example.com/data", function(resp)
  mp.msg.info("Status: " .. resp.status)
  mp.msg.info("Body: " .. resp.body)
end)

-- GET with custom headers
mpvrx.get("https://api.example.com/protected",
  { ["Authorization"] = "Bearer your_token" },
  function(resp)
    if resp.status == 200 then
      mp.osd_message("Got data!")
    end
  end
)

-- POST with JSON body
mpvrx.post("https://api.example.com/submit",
  '{"key": "value"}',
  { ["Content-Type"] = "application/json" },
  function(resp)
    mp.msg.info("Response: " .. resp.body)
  end
)

-- PUT request
mpvrx.put("https://api.example.com/update",
  '{"name": "new_name"}',
  nil,
  function(resp) mp.msg.info("Updated: " .. resp.status) end
)

-- DELETE request
mpvrx.delete("https://api.example.com/delete/123",
  function(resp) mp.msg.info("Deleted: " .. resp.status) end
)
```

## Usage (JS Scripts)

Since each JavaScript script in mpv runs in its own isolated context, you must import the `mpvrx` module using `require()`:

```javascript
var mpvrx = require("mpvrx_curl");

// GET request
mpvrx.get("https://api.example.com/data", function(resp) {
  mp.msg.info("Status: " + resp.status);
  mp.msg.info("Body: " + resp.body);
});

// POST with JSON
mpvrx.post("https://api.example.com/submit", '{"key":"value"}',
  { "Content-Type": "application/json" },
  function(resp) { mp.msg.info(resp.body); }
);

// DELETE request
mpvrx.del("https://api.example.com/delete/123", function(resp) {
  mp.msg.info("Deleted: " + resp.status);
});
```

## Response Object

Every callback receives a response table/object:

| Field   | Type   | Description                                      |
|---------|--------|--------------------------------------------------|
| status  | number | HTTP status code (200, 404, etc.) or 0 on error  |
| body    | string | Response body text                               |
| headers | object | Response headers as key-value pairs              |
| ok      | boolean | True for HTTP 2xx responses                     |
| url     | string | Final URL after redirects                        |
| error   | string | Error message if request failed, or null/empty   |

## Real-World Example: ZenQuotes API

A working Lua script that fetches a random quote on playback start:

```lua
local mpvrx = dofile("~~/scripts/mpvrx_curl.lua")

function show_random_quote()
  mpvrx.get("https://zenquotes.io/api/random", function(resp)
    if resp.status == 200 then
      local ok, data = pcall(mpvrx._json_decode, resp.body)
      if ok and data and #data > 0 then
        local quote = data[1]
        mp.osd_message(quote.q .. "\n- " .. quote.a, 10)
      end
    end
  end)
end

mp.register_event("file-loaded", show_random_quote)
```

> The same also in JS:
> ```javascript
> var mpvrx = require("mpvrx_curl");
>
> mp.register_event("file-loaded", function() {
>   mpvrx.get("https://zenquotes.io/api/random", function(resp) {
>     if (resp.status == 200) {
>       var data = JSON.parse(resp.body);
>       mp.osd_message(data[0].q + "\n- " + data[0].a, 10);
>     }
>   });
> });
> ```

## More Real-World Examples

### Check your public IP

```lua
mpvrx.get("https://api.ipify.org?format=json", function(resp)
  if resp.status == 200 then
    local ok, data = pcall(mpvrx._json_decode, resp.body)
    if ok then
      mp.osd_message("Your IP: " .. data.ip, 5)
    end
  end
end)
```

### Fetch weather

```lua
mpvrx.get("https://wttr.in/London?format=j1", function(resp)
  if resp.status == 200 then
    local ok, data = pcall(mpvrx._json_decode, resp.body)
    if ok and data and data.current_condition then
      local cc = data.current_condition[1]
      local temp = cc.temp_C
      local desc = cc.weatherDesc[1].value
      mp.osd_message("London: " .. temp .. "°C, " .. desc, 8)
    end
  end
end)
```

### Download a subtitle or file

```lua
mpvrx.get("https://example.com/subtitle.srt", function(resp)
  if resp.status == 200 then
    local f = io.open(mp.get_property("working-directory") .. "/sub.srt", "w")
    if f then
      f:write(resp.body)
      f:close()
      mp.osd_message("Subtitle saved!")
    end
  end
end)
```

## Advanced: Raw curl() Call

For full control, use `mpvrx.curl()` directly:

```lua
mpvrx.curl({
  url = "https://api.example.com/data",
  method = "GET",
  headers = { ["Accept"] = "application/json" },
  body = "",                    -- request body text (only for methods that permit body)
  timeout = 15,                 -- connection/read/write timeout in seconds (default 30)
  insecure = false,             -- set to true to trust all SSL/TLS certificates (default false)
  output_file = "/path/to/save",-- write response body directly to this file path
  upload_file = "/path/to/send",-- read request body directly from this file path
  base64_response = false,      -- set to true to encode response body as a base64 string
  base64_body = false,          -- set to true if 'body' contains a base64-encoded request payload
  follow_redirects = true,      -- set to false to disable redirect following (default true)
  json = nil,                   -- optional Lua table / JS object encoded as JSON request body
}, function(resp)
  -- handle response
end)
```

## Response Object

Every callback receives a response table/object with the following fields:

| Field   | Type   | Description                                                           |
|---------|--------|-----------------------------------------------------------------------|
| status  | number | HTTP status code (200, 404, etc.) or 0 on error                        |
| body    | string | Response body text (or absolute file path if `output_file` was used)  |
| headers | object | Response headers. Duplicates (like `Set-Cookie`) are stored in arrays.|
| ok      | boolean | True for HTTP 2xx responses                                          |
| url     | string | Final URL after redirects                                             |
| error   | string | Error message if request failed, or null/empty                        |

## Things to Know

- **Requests are async** – the callback fires when the response arrives. The script continues running in the meantime.
- **Timeout** defaults to 30 seconds. Set `timeout` in the params to change it.
- **Redirects** are followed automatically by default. Set `follow_redirects = false` to disable.
- **SSL/TLS** verification is on by default. Set `insecure = true` to bypass verification (like `curl -k`).
- **Large File Downloads/Uploads**: Use `output_file` (for downloads) and `upload_file` (for uploads) to stream files directly to and from the disk. This bypasses memory limitations and prevents crashes.
- **Binary Data**: Use `base64_response = true` and `base64_body = true` to safely transfer non-text binary payloads between script and player.
- **Scripts must be enabled** in mpvRx settings (Advanced → Scripts).
- Your script only needs `dofile("~~/scripts/mpvrx_curl.lua")` – no other setup required.
- JS scripts use `var mpvrx = require("mpvrx_curl");`. mpvRx wires the JS module search path automatically.
