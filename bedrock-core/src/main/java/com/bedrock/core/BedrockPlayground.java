package com.bedrock.core;

/**
 * Provides the visual testing interface (Playground) in Swagger style for Bedrock,
 * built purely with Text Blocks in HTML/CSS/JS and dark theme.
 */
public class BedrockPlayground {
    
    public static String getHtml() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>🦖 Bedrock Playground</title>
            <style>
                :root {
                    --bg-color: #121212;
                    --panel-bg: #1e1e1e;
                    --text-color: #e0e0e0;
                    --accent-color: #4caf50;
                    --border-color: #333;
                    --method-get: #61affe;
                    --method-post: #49cc90;
                    --method-put: #fca130;
                    --method-delete: #f93e3e;
                    --method-patch: #fca130;
                }
                body {
                    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    background-color: var(--bg-color);
                    color: var(--text-color);
                    margin: 0;
                    padding: 20px;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                }
                .container {
                    width: 100%;
                    max-width: 900px;
                }
                h1 {
                    text-align: center;
                    color: var(--accent-color);
                    margin-bottom: 5px;
                }
                p.subtitle {
                    text-align: center;
                    color: #888;
                    margin-bottom: 30px;
                }
                .route-panel {
                    background-color: var(--panel-bg);
                    border: 1px solid var(--border-color);
                    border-radius: 8px;
                    padding: 15px;
                    margin-bottom: 15px;
                    box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                }
                .route-header {
                    display: flex;
                    align-items: center;
                    gap: 15px;
                    margin-bottom: 10px;
                }
                .method {
                    font-weight: bold;
                    padding: 5px 10px;
                    border-radius: 4px;
                    font-size: 14px;
                    text-transform: uppercase;
                    width: 60px;
                    text-align: center;
                }
                .method.GET { background-color: rgba(97, 175, 254, 0.2); color: var(--method-get); }
                .method.POST { background-color: rgba(73, 204, 144, 0.2); color: var(--method-post); }
                .method.PUT, .method.PATCH { background-color: rgba(252, 161, 48, 0.2); color: var(--method-put); }
                .method.DELETE { background-color: rgba(249, 62, 62, 0.2); color: var(--method-delete); }
                
                .path {
                    font-family: monospace;
                    font-size: 16px;
                    flex-grow: 1;
                }
                button {
                    background-color: #333;
                    color: white;
                    border: 1px solid #555;
                    padding: 8px 15px;
                    border-radius: 4px;
                    cursor: pointer;
                    transition: 0.2s;
                }
                button:hover {
                    background-color: #444;
                }
                button.send-btn {
                    background-color: var(--accent-color);
                    border: none;
                    font-weight: bold;
                    color: #121212;
                }
                button.send-btn:hover {
                    background-color: #45a049;
                }
                
                .test-area {
                    margin-top: 15px;
                    display: none;
                    flex-direction: column;
                    gap: 15px;
                    border-top: 1px solid var(--border-color);
                    padding-top: 15px;
                }
                
                .input-group {
                    display: flex;
                    flex-direction: column;
                    gap: 5px;
                }
                
                label {
                    font-size: 13px;
                    color: #aaa;
                    font-weight: 500;
                }
                
                input, textarea {
                    background-color: #2d2d2d;
                    border: 1px solid #444;
                    color: #fff;
                    padding: 10px;
                    border-radius: 4px;
                    font-family: monospace;
                    font-size: 14px;
                }
                
                input:focus, textarea:focus {
                    outline: none;
                    border-color: var(--accent-color);
                }
                
                textarea {
                    resize: vertical;
                    min-height: 80px;
                }
                
                .response-area {
                    background-color: #0d0d0d;
                    padding: 15px;
                    border-radius: 4px;
                    border: 1px solid #333;
                    margin-top: 5px;
                }
                
                .status {
                    font-weight: bold;
                    margin-bottom: 10px;
                    display: inline-block;
                }
                .status.success { color: #49cc90; }
                .status.error { color: #f93e3e; }
                
                pre {
                    margin: 0;
                    white-space: pre-wrap;
                    word-wrap: break-word;
                    color: #a6e22e;
                    font-size: 14px;
                }
                
                .loading {
                    text-align: center;
                    padding: 40px;
                    color: #888;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🦖 Bedrock Playground</h1>
                <p class="subtitle">Interactive Route Inspection and API Testing</p>
                
                <div id="routes-container">
                    <div class="loading">Syncing routes with Bedrock Server...</div>
                </div>
            </div>

            <script>
                document.addEventListener('DOMContentLoaded', () => {
                    fetch('/bedrock/api/routes')
                        .then(res => res.json())
                        .then(routes => {
                            const container = document.getElementById('routes-container');
                            
                            const publicRoutes = routes.filter(r => !r.path.startsWith('/bedrock'));
                            
                            if (!publicRoutes || publicRoutes.length === 0) {
                                container.innerHTML = '<div class="loading">No application routes registered.</div>';
                                return;
                            }
                            
                            container.innerHTML = '';
                            publicRoutes.forEach((route, index) => {
                                const id = 'route-' + index;
                                const hasBody = ['POST', 'PUT', 'PATCH'].includes(route.method);
                                
                                const pathVariables = [...route.path.matchAll(/\\{([^}]+)\\}/g)].map(m => m[1]);
                                
                                let pathInputs = '';
                                if (pathVariables.length > 0) {
                                    pathInputs = pathVariables.map(v => `
                                        <div class="input-group">
                                            <label>PathParam: {${v}}</label>
                                            <input type="text" id="${id}-param-${v}" placeholder="Value for ${v}" autocomplete="off">
                                        </div>
                                    `).join('');
                                }
                                
                                const html = `
                                    <div class="route-panel">
                                        <div class="route-header">
                                            <span class="method ${route.method}">${route.method}</span>
                                            <span class="path">${route.path}</span>
                                            <button onclick="toggleTest('${id}')">Test ⚡</button>
                                        </div>
                                        <div class="test-area" id="${id}-test-area">
                                            ${pathInputs}
                                            ${hasBody ? `
                                            <div class="input-group">
                                                <label>Request Body (JSON)</label>
                                                <textarea id="${id}-body">{\n  \n}</textarea>
                                            </div>
                                            ` : ''}
                                            <div class="input-group">
                                                <button class="send-btn" onclick="sendRequest('${id}', '${route.method}', '${route.path}')">Execute Request</button>
                                            </div>
                                            <div class="response-area" style="display: none;" id="${id}-response">
                                                <div class="status" id="${id}-status"></div>
                                                <pre id="${id}-result"></pre>
                                            </div>
                                        </div>
                                    </div>
                                `;
                                container.innerHTML += html;
                            });
                        })
                        .catch(err => {
                            document.getElementById('routes-container').innerHTML = 
                                '<div class="loading" style="color:#f93e3e">Error connecting to Bedrock API. Is the server running?</div>';
                        });
                });

                function toggleTest(id) {
                    const el = document.getElementById(id + '-test-area');
                    el.style.display = el.style.display === 'flex' ? 'none' : 'flex';
                }

                async function sendRequest(id, method, path) {
                    const responseDiv = document.getElementById(id + '-response');
                    const statusEl = document.getElementById(id + '-status');
                    const resultEl = document.getElementById(id + '-result');
                    
                    responseDiv.style.display = 'block';
                    statusEl.textContent = 'Sending...';
                    statusEl.className = 'status';
                    resultEl.textContent = '';
                    
                    let finalPath = path;
                    const pathVariables = [...path.matchAll(/\\{([^}]+)\\}/g)].map(m => m[1]);
                    pathVariables.forEach(v => {
                        const inputVal = document.getElementById(`${id}-param-${v}`).value;
                        finalPath = finalPath.replace(`{${v}}`, inputVal || `1`);
                    });
                    
                    const options = {
                        method: method,
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    };
                    
                    const bodyEl = document.getElementById(id + '-body');
                    if (bodyEl && ['POST', 'PUT', 'PATCH'].includes(method)) {
                        try {
                            const bodyData = bodyEl.value.trim();
                            if(bodyData) {
                                JSON.parse(bodyData); 
                                options.body = bodyData;
                            }
                        } catch(e) {
                            statusEl.textContent = 'ERROR 400 - Invalid JSON';
                            statusEl.className = 'status error';
                            resultEl.textContent = "Check the JSON syntax provided in the Request Body.";
                            return;
                        }
                    }

                    try {
                        const startTime = performance.now();
                        const res = await fetch(finalPath, options);
                        const endTime = performance.now();
                        const duration = Math.round(endTime - startTime);
                        
                        statusEl.textContent = `Status: ${res.status} ${res.statusText} (${duration}ms)`;
                        statusEl.className = 'status ' + (res.ok ? 'success' : 'error');
                        
                        // Show all headers
                        let headersTxt = '// Headers\\n';
                        res.headers.forEach((val, key) => {
                            headersTxt += `${key}: ${val}\\n`;
                        });
                        
                        const text = await res.text();
                        try {
                            const json = JSON.parse(text);
                            resultEl.textContent = headersTxt + '\\n' + JSON.stringify(json, null, 2);
                        } catch(e) {
                            resultEl.textContent = headersTxt + '\\n' + (text || '<empty>');
                        }
                    } catch(err) {
                        statusEl.textContent = 'Connection Failed';
                        statusEl.className = 'status error';
                        resultEl.textContent = err.toString();
                    }
                }
            </script>
        </body>
        </html>
        """;
    }
}
