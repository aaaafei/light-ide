(function () {
  var state = { listenPort: 0, settings: {}, projects: [], runs: [] };
  var selected = { projectId: null, configId: null };
  var dirty = false;
  var logSource = null;
  var logRunId = null;
  var toastTimer = null;

  document.getElementById("addProject").addEventListener("submit", onAddProject);
  document.getElementById("settingsBtn").addEventListener("click", openSettings);
  document.getElementById("settingsForm").addEventListener("submit", onSaveSettings);
  document.getElementById("closeSettings").addEventListener("click", closeSettings);
  document.getElementById("editor").addEventListener("submit", onSaveConfig);
  document.getElementById("editor").addEventListener("input", function () { dirty = true; });
  document.getElementById("editor").addEventListener("change", function () {
    dirty = true;
    syncTypeFields();
  });
  document.getElementById("startBtn").addEventListener("click", function () { runAction("start"); });
  document.getElementById("stopBtn").addEventListener("click", function () { runAction("stop"); });
  document.getElementById("restartBtn").addEventListener("click", function () { runAction("restart"); });
  document.getElementById("copyAttach").addEventListener("click", onCopy);
  document.getElementById("deleteConfig").addEventListener("click", onDeleteConfig);

  load(true).then(function () {
    setInterval(function () { load(false); }, 3000);
  });

  function api(method, url, body) {
    var options = { method: method, headers: {} };
    if (body) {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(body);
    }
    return fetch(url, options).then(function (res) {
      return res.text().then(function (text) {
        var data = text ? JSON.parse(text) : null;
        if (!res.ok) {
          throw new Error(data && data.error ? data.error : res.statusText);
        }
        return data;
      });
    });
  }

  function load(fill) {
    return api("GET", "/api/state").then(function (data) {
      state = data;
      document.getElementById("listen").textContent = "127.0.0.1:" + data.listenPort;
      renderSidebar();
      var config = findConfig(selected.projectId, selected.configId);
      document.getElementById("empty").hidden = !!config;
      document.getElementById("workspace").hidden = !config;
      if (!config) {
        connectLogs(null);
        return;
      }
      if (fill && !dirty) fillEditor();
      renderToolbar();
      var run = currentRun();
      if ((run && run.id !== logRunId) || (!run && logRunId)) connectLogs(run);
    }).catch(function (error) {
      toast(error.message);
    });
  }

  var sidebarDrawn = "";

  function renderSidebar() {
    var key = sidebarKey();
    if (key === sidebarDrawn) return;
    sidebarDrawn = key;
    var root = document.getElementById("projects");
    root.innerHTML = "";
    if (!state.projects.length) {
      var empty = document.createElement("p");
      empty.className = "note";
      empty.textContent = "还没有项目。";
      root.appendChild(empty);
    }
    state.projects.forEach(function (project) {
      var block = document.createElement("section");
      block.className = "project";
      var head = document.createElement("div");
      head.className = "project-head";
      var title = document.createElement("div");
      title.className = "project-name";
      title.textContent = project.name;
      title.title = project.root;
      if (project.webapp) {
        var badge = document.createElement("small");
        badge.textContent = " war";
        title.appendChild(badge);
      }
      var del = document.createElement("button");
      del.type = "button";
      del.textContent = "删除";
      del.addEventListener("click", function () { removeProject(project.id); });
      head.appendChild(title);
      head.appendChild(del);
      block.appendChild(head);
      project.configs.forEach(function (config) {
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "config" + (config.id === selected.configId ? " selected" : "");
        var name = document.createElement("span");
        name.textContent = config.name;
        var type = document.createElement("small");
        type.textContent = config.type;
        btn.appendChild(name);
        btn.appendChild(type);
        var active = activeRun(config.id);
        if (active) {
          var dot = document.createElement("i");
          dot.className = active.state;
          btn.appendChild(dot);
        }
        btn.addEventListener("click", function () { selectConfig(project.id, config.id); });
        block.appendChild(btn);
      });
      var add = document.createElement("div");
      add.className = "add-config";
      ["maven", "gradle", "tomcat", "script"].forEach(function (type) {
        var button = document.createElement("button");
        button.type = "button";
        button.textContent = type;
        button.addEventListener("click", function () { createConfig(project.id, type); });
        add.appendChild(button);
      });
      block.appendChild(add);
      root.appendChild(block);
    });
    renderOrphans();
  }

  function sidebarKey() {
    var parts = [];
    state.projects.forEach(function (project) {
      parts.push(project.id, project.name, project.webapp ? "1" : "0");
      project.configs.forEach(function (config) {
        var active = activeRun(config.id);
        parts.push(config.id, config.name, config.type, active ? active.state : "-", config.id === selected.configId ? "*" : "");
      });
    });
    state.runs.forEach(function (run) {
      if (run.state === "orphan") parts.push("orphan", run.id, String(run.pid));
    });
    return parts.join("|");
  }

  function renderOrphans() {
    var box = document.getElementById("orphans");
    box.innerHTML = "";
    var orphans = [];
    state.runs.forEach(function (run) {
      if (run.state === "orphan") orphans.push(run);
    });
    if (!orphans.length) return;
    var title = document.createElement("h2");
    title.textContent = "残留进程";
    box.appendChild(title);
    orphans.forEach(function (run) {
      var row = document.createElement("div");
      row.className = "orphan";
      var label = document.createElement("span");
      label.textContent = run.name + "  pid " + run.pid;
      var button = document.createElement("button");
      button.type = "button";
      button.textContent = "结束";
      button.addEventListener("click", function () {
        api("POST", "/api/runs/" + run.id + "/stop").then(function () {
          return load(false);
        }).catch(function (error) { toast(error.message); });
      });
      row.appendChild(label);
      row.appendChild(button);
      box.appendChild(row);
    });
  }

  function selectConfig(projectId, configId) {
    if (dirty && selected.configId !== configId && !window.confirm("配置还没保存，放弃修改？")) return;
    selected.projectId = projectId;
    selected.configId = configId;
    dirty = false;
    document.getElementById("empty").hidden = true;
    document.getElementById("workspace").hidden = false;
    fillEditor();
    renderSidebar();
    renderToolbar();
    connectLogs(currentRun());
  }

  function fillEditor() {
    var config = findConfig(selected.projectId, selected.configId);
    if (!config) return;
    document.getElementById("name").value = config.name || "";
    document.getElementById("type").value = config.type;
    document.getElementById("goals").value = config.type === "maven" ? (config.goals || "") : "";
    document.getElementById("tasks").value = config.type === "gradle" ? (config.goals || "") : "";
    document.getElementById("args").value = config.args || "";
    document.getElementById("script").value = config.script || "";
    document.getElementById("cwd").value = config.cwd || "";
    document.getElementById("catalinaBase").value = config.catalinaBase || "";
    document.getElementById("warPath").value = config.warPath || "";
    document.getElementById("debug").checked = !!config.debug;
    document.getElementById("debugPort").value = config.debugPort || 5005;
    document.getElementById("env").value = envToText(config.env);
    syncTypeFields();
    dirty = false;
  }

  function syncTypeFields() {
    var type = document.getElementById("type").value;
    var rows = document.querySelectorAll("#editor [data-types]");
    for (var i = 0; i < rows.length; i++) {
      rows[i].hidden = rows[i].getAttribute("data-types").split(" ").indexOf(type) < 0;
    }
    var port = document.getElementById("debugPort");
    port.disabled = type === "gradle";
    var hint = document.getElementById("hint");
    if (type === "maven") hint.textContent = "调试只注入到 spring-boot:run 的 jvmArguments，不会写进 MAVEN_OPTS。";
    else if (type === "gradle") hint.textContent = "始终使用 --no-daemon。调试是 bootRun --debug-jvm，端口固定 5005，进程会先暂停。";
    else if (type === "tomcat") hint.textContent = "启动 catalina.bat run。调试使用 jpda run。停止时会先 catalina stop。";
    else hint.textContent = "脚本不注入调试参数。bat 和 cmd 用 cmd 运行，ps1 用 PowerShell 运行。";
  }

  function readEditor() {
    var type = document.getElementById("type").value;
    return {
      name: document.getElementById("name").value.trim(),
      type: type,
      goals: type === "gradle" ? document.getElementById("tasks").value.trim() : document.getElementById("goals").value.trim(),
      args: document.getElementById("args").value.trim(),
      script: document.getElementById("script").value.trim(),
      cwd: document.getElementById("cwd").value.trim(),
      catalinaBase: document.getElementById("catalinaBase").value.trim(),
      warPath: document.getElementById("warPath").value.trim(),
      debug: document.getElementById("debug").checked,
      debugPort: parseInt(document.getElementById("debugPort").value, 10) || 5005,
      env: envFromText(document.getElementById("env").value)
    };
  }

  function renderToolbar() {
    var project = findProject(selected.projectId);
    var config = findConfig(selected.projectId, selected.configId);
    var run = currentRun();
    var title = document.getElementById("runTitle");
    var meta = document.getElementById("runMeta");
    if (!config) return;
    title.textContent = (project ? project.name + " / " : "") + config.name;
    var bits = [config.type];
    if (run) {
      bits.push(run.state === "running" ? "运行中" : run.state === "orphan" ? "残留" : "已退出");
      if (run.pid) bits.push("pid " + run.pid);
      if (run.exitCode !== null && run.exitCode !== undefined && run.state === "exited") bits.push("代码 " + run.exitCode);
      if (run.command) bits.push(run.command);
    }
    meta.textContent = bits.join("  ·  ");
    var alive = run && run.state !== "exited";
    document.getElementById("startBtn").disabled = !!alive;
    document.getElementById("stopBtn").disabled = !alive;
    document.getElementById("restartBtn").disabled = !config;
    var copy = document.getElementById("copyAttach");
    copy.hidden = !(run && run.attach && run.state !== "exited");
  }

  function currentRun() {
    var list = [];
    state.runs.forEach(function (run) {
      if (run.configId === selected.configId) list.push(run);
    });
    for (var i = list.length - 1; i >= 0; i--) {
      if (list[i].state !== "exited") return list[i];
    }
    return list.length ? list[list.length - 1] : null;
  }

  function activeRun(configId) {
    for (var i = state.runs.length - 1; i >= 0; i--) {
      if (state.runs[i].configId === configId && state.runs[i].state !== "exited") return state.runs[i];
    }
    return null;
  }

  function connectLogs(run) {
    if (logSource) {
      logSource.close();
      logSource = null;
    }
    logRunId = run ? run.id : null;
    var log = document.getElementById("log");
    log.innerHTML = "";
    if (!run) return;
    var source = new EventSource("/api/runs/" + run.id + "/logs");
    logSource = source;
    source.addEventListener("history", function (event) {
      var data = JSON.parse(event.data);
      log.innerHTML = "";
      (data.lines || []).forEach(function (line) { appendLine(line); });
      log.scrollTop = log.scrollHeight;
    });
    source.addEventListener("line", function (event) {
      appendLine(JSON.parse(event.data));
      var near = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
      if (near) log.scrollTop = log.scrollHeight;
    });
    source.addEventListener("end", function () {
      source.close();
      if (logSource === source) logSource = null;
      load(false);
    });
  }

  function appendLine(line) {
    var row = document.createElement("div");
    row.className = "line " + (line.stream || "out");
    row.textContent = line.text == null ? "" : String(line.text);
    document.getElementById("log").appendChild(row);
  }

  function onAddProject(event) {
    event.preventDefault();
    var path = document.getElementById("projectPath").value.trim();
    api("POST", "/api/projects", { path: path }).then(function (project) {
      document.getElementById("projectPath").value = "";
      selected.projectId = project.id;
      selected.configId = project.configs.length ? project.configs[0].id : null;
      dirty = false;
      return load(true);
    }).then(function () {
      if (selected.configId) selectConfig(selected.projectId, selected.configId);
    }).catch(function (error) { toast(error.message); });
  }

  function createConfig(projectId, type) {
    api("POST", "/api/projects/" + projectId + "/configs", { type: type }).then(function (config) {
      selected.projectId = projectId;
      selected.configId = config.id;
      dirty = false;
      return load(true);
    }).catch(function (error) { toast(error.message); });
  }

  function removeProject(projectId) {
    if (!window.confirm("删除这个项目？目录里的代码不会动。")) return;
    api("DELETE", "/api/projects/" + projectId).then(function () {
      if (selected.projectId === projectId) {
        selected.projectId = null;
        selected.configId = null;
        dirty = false;
      }
      return load(true);
    }).catch(function (error) { toast(error.message); });
  }

  function onSaveConfig(event) {
    event.preventDefault();
    saveConfig().catch(function (error) { toast(error.message); });
  }

  function saveConfig() {
    var body;
    try {
      body = readEditor();
    } catch (error) {
      return Promise.reject(error);
    }
    return api("PUT", "/api/projects/" + selected.projectId + "/configs/" + selected.configId, body).then(function () {
      dirty = false;
      toast("配置已保存");
      return load(true);
    });
  }

  function onDeleteConfig() {
    if (!window.confirm("删除这个运行配置？")) return;
    api("DELETE", "/api/projects/" + selected.projectId + "/configs/" + selected.configId).then(function () {
      selected.configId = null;
      dirty = false;
      return load(true);
    }).catch(function (error) { toast(error.message); });
  }

  function runAction(action) {
    var pending = dirty ? saveConfig() : Promise.resolve();
    pending.then(function () {
      if (action === "start") {
        return api("POST", "/api/runs", { projectId: selected.projectId, configId: selected.configId });
      }
      var run = currentRun();
      if (!run) throw new Error("没有可操作的运行实例");
      return api("POST", "/api/runs/" + run.id + "/" + action);
    }).then(function () {
      return load(false);
    }).catch(function (error) { toast(error.message); });
  }

  function onCopy() {
    var run = currentRun();
    if (!run || !run.attach) return;
    var text = JSON.stringify(run.attach, null, 2);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        toast("已复制，可粘贴到 .vscode/launch.json 的 configurations");
      }, function () { fallbackCopy(text); });
    } else {
      fallbackCopy(text);
    }
  }

  function fallbackCopy(text) {
    var area = document.createElement("textarea");
    area.value = text;
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    document.body.removeChild(area);
    toast("已复制，可粘贴到 .vscode/launch.json 的 configurations");
  }

  function openSettings() {
    var settings = state.settings || {};
    document.getElementById("setPort").value = settings.port || 4717;
    document.getElementById("setJavaHome").value = settings.javaHome || "";
    document.getElementById("setMaven").value = settings.mavenCmd || "mvn";
    document.getElementById("setGradle").value = settings.gradleCmd || "gradle";
    document.getElementById("setCatalina").value = settings.catalinaHome || "";
    document.getElementById("settingsDialog").hidden = false;
  }

  function closeSettings() {
    document.getElementById("settingsDialog").hidden = true;
  }

  function onSaveSettings(event) {
    event.preventDefault();
    api("PUT", "/api/settings", {
      port: parseInt(document.getElementById("setPort").value, 10),
      javaHome: document.getElementById("setJavaHome").value.trim(),
      mavenCmd: document.getElementById("setMaven").value.trim(),
      gradleCmd: document.getElementById("setGradle").value.trim(),
      catalinaHome: document.getElementById("setCatalina").value.trim()
    }).then(function (data) {
      closeSettings();
      toast(data.restartRequired ? "已保存。端口要重新启动 light-ide 后才生效。" : "设置已保存");
      return load(false);
    }).catch(function (error) { toast(error.message); });
  }

  function findProject(id) {
    for (var i = 0; i < state.projects.length; i++) {
      if (state.projects[i].id === id) return state.projects[i];
    }
    return null;
  }

  function findConfig(projectId, configId) {
    var project = findProject(projectId);
    if (!project || !configId) return null;
    for (var i = 0; i < project.configs.length; i++) {
      if (project.configs[i].id === configId) return project.configs[i];
    }
    return null;
  }

  function envToText(env) {
    var lines = [];
    if (!env) return "";
    Object.keys(env).forEach(function (key) { lines.push(key + "=" + env[key]); });
    return lines.join("\n");
  }

  function envFromText(text) {
    var env = {};
    text.split(/\r?\n/).forEach(function (line) {
      if (!line.trim()) return;
      var eq = line.indexOf("=");
      if (eq <= 0) throw new Error("环境变量需要写成 KEY=VALUE：" + line);
      env[line.substring(0, eq).trim()] = line.substring(eq + 1);
    });
    return env;
  }

  function toast(message) {
    var el = document.getElementById("toast");
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 4200);
  }
})();
