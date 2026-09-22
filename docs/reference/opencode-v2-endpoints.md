# 桌面版 opencode v2 接口参考（我们 App 用到的那部分）

> 自动生成，来源：正在运行的桌面版 `2.0.11` 的 `/openapi.json`。
> **这份文档是改写 `OpenCodeApi.kt` / `Dtos.kt` 的唯一依据** —— 不要再凭类型定义或文档猜。

契约原始文件：`docs/reference/opencode-v2-openapi.json`（113 个端点）


---

## `GET /api/info`

**用途**：服务信息（可当探活：确认类型与字段）

**摘要**：Get server info


**200 响应**：
```
{
 "type": "object",
 "properties": {
  "version": {
   "type": "string"
  },
  "pid": {
   "type": "integer",
   "minimum": 0
  },
  "urls": {
   "type": "array",
   "items": {
    "type": "string"
   }
  },
  "paths": {
   "type": "object",
   "properties": {
    "tmp": {
     "type": "string"
    }
   },
   "required": [
    "tmp"
   ],
   "additionalProperties": false
  }
 },
 "required": [
  "version",
  "pid",
  "urls",
  "paths"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/location`

**用途**：当前工作目录 / 项目位置（代替 v1 的 /path）

**摘要**：Get location


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "directory": {
   "type": "string"
  },
  "project": {
   "type": "object",
   "properties": {
    "id": {
     "type": "string"
    },
    "directory": {
     "type": "string"
    },
    "canonical": {
     "type": "string"
    }
   },
   "required": [
    "id",
    "directory",
    "canonical"
   ],
   "additionalProperties": false
  }
 },
 "required": [
  "directory",
  "project"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/project`

**用途**：项目列表（即工作区列表）

**摘要**：List projects


**200 响应**：
```
{
 "type": "array",
 "items": {
  "type": "object",
  "properties": {
   "id": {
    "type": "string"
   },
   "canonical": {
    "type": "string"
   },
   "vcs": {
    "type": "string",
    "pattern": "^[a-z][a-z0-9._-]*$"
   },
   "name": {
    "type": "string"
   },
   "icon": {
    "type": "object",
    "properties": {
     "url": {
      "type": "string"
     },
     "override": {
      "type": "string"
     },
     "color": {
      "type": "string"
     }
    },
    "additionalProperties": false
   },
   "commands": {
    "type": "object",
    "properties": {
     "start": {
      "type": "string",
      "description": "Startup script to run when creating a new workspace (worktree)"
     }
    },
    "additionalProperties": false
   },
   "time": {
    "type": "object",
    "properties": {
     "created": {
      "type": "integer",
      "minimum": 0
     },
     "updated": {
      "type": "integer",
      "minimum": 0
     }
    },
    "required": [
     "created",
     "updated"
    ],
    "additionalProperties": false
   },
   "sandboxes": {
    "type": "array",
    "items": {
     "type": "string"
    }
   }
  },
  "required": [
   "id",
   "canonical",
   "time",
   "sandboxes"
  ],
  "additionalProperties": false
 }
}
```

---

## `GET /api/session`

**用途**：会话列表

**摘要**：List sessions


**参数**：
- `limit` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Maximum number of sessions to return. Defaults to the newest 50 sessions."}
- `order` (query, 可选): {"anyOf": [{"type": "string", "enum": ["...", "..."]}, {"type": "null"}], "description": "Session order for the first page. Use desc for newest first or asc for oldest first."}
- `search` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}]}
- `parentID` (query, 可选): {"anyOf": [{"anyOf": ["...", "..."], "description": "Filter by parent session. Use null to return only root sessions."}, {"type": "null"}]}
- `directory` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}]}
- `project` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}]}
- `subpath` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}]}
- `cursor` (query, 可选): {"anyOf": [{"type": "string", "description": "Opaque pagination cursor returned as cursor.previous or cursor.next in the previous response."}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string",
      "pattern": "^ses"
     },
     "parentID": {
      "type": "string",
      "pattern": "^ses"
     },
     "fork": {
      "type": "object",
      "properties": {
       "sessionID": "...",
       "boundary": "..."
      },
      "required": [
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "projectID": {
      "type": "string"
     },
     "agent": {
      "type": "string"
     },
     "model": {
      "type": "object",
      "properties": {
       "id": "...",
       "providerID": "...",
       "variant": "..."
      },
      "required": [
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "cost": {
      "type": "number"
     },
     "tokens": {
      "type": "object",
      "properties": {
       "input": "...",
       "output": "...",
       "reasoning": "...",
       "cache": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "outcome": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "..."
      ]
     },
     "time": {
      "type": "object",
      "properties": {
       "created": "...",
       "updated": "...",
       "idle": "...",
       "viewed": "...",
       "archived": "..."
      },
      "required": [
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "title": {
      "type": "string"
     },
     "subpath": {
      "type": "string"
     },
     "metadata": {
      "type": "object"
     },
     "permissions": {
      "type": "array",
      "items": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "revert": {
      "type": "object",
      "properties": {
       "messageID": "...",
       "partID": "...",
       "snapshot": "...",
       "files": "..."
      },
      "required": [
       "..."
      ],
      "additionalProperties": false
     },
     "location": {
      "type": "object",
      "properties": {
       "directory": "..."
      },
      "required": [
       "..."
      ],
      "additionalProperties": false
     }
    },
    "required": [
     "id",
     "projectID",
     "cost",
     "tokens",
     "time",
     "location"
    ],
    "additionalProperties": false
   }
  },
  "cursor": {
   "type": "object",
   "properties": {
    "previous": {
     "anyOf": [
      {
       "type": "..."
      },
      {
       "type": "..."
      }
     ]
    },
    "next": {
     "anyOf": [
      {
       "type": "..."
      },
      {
       "type": "..."
      }
     ]
    }
   },
   "additionalProperties": false
  }
 },
 "required": [
  "data",
  "cursor"
 ],
 "additionalProperties": false
}
```

---

## `POST /api/session`

**用途**：新建会话

**摘要**：Create session


**请求体**：
```
{
 "type": "object",
 "properties": {
  "id": {
   "anyOf": [
    {
     "type": "string",
     "pattern": "^ses"
    },
    {
     "type": "null"
    }
   ]
  },
  "title": {
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  },
  "agent": {
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  },
  "model": {
   "anyOf": [
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "..."
      },
      "providerID": {
       "type": "..."
      },
      "variant": {
       "type": "..."
      }
     },
     "required": [
      "id",
      "providerID"
     ],
     "additionalProperties": false
    },
    {
     "type": "null"
    }
   ]
  },
  "location": {
   "anyOf": [
    {
     "type": "object",
     "properties": {
      "directory": {
       "type": "..."
      }
     },
     "required": [
      "directory"
     ],
     "additionalProperties": false
    },
    {
     "type": "null"
    }
   ]
  },
  "metadata": {
   "anyOf": [
    {
     "type": "object"
    },
    {
     "type": "null"
    }
   ]
  },
  "permissions": {
   "anyOf": [
    {
     "type": "array",
     "items": {
      "type": "object",
      "properties": {
       "action": "...",
       "resource": "...",
       "effect": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "additionalProperties": false
}
```

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "object",
   "properties": {
    "id": {
     "type": "string",
     "pattern": "^ses"
    },
    "parentID": {
     "type": "string",
     "pattern": "^ses"
    },
    "fork": {
     "type": "object",
     "properties": {
      "sessionID": {
       "type": "...",
       "pattern": "..."
      },
      "boundary": {
       "anyOf": "..."
      }
     },
     "required": [
      "sessionID",
      "boundary"
     ],
     "additionalProperties": false
    },
    "projectID": {
     "type": "string"
    },
    "agent": {
     "type": "string"
    },
    "model": {
     "type": "object",
     "properties": {
      "id": {
       "type": "..."
      },
      "providerID": {
       "type": "..."
      },
      "variant": {
       "type": "..."
      }
     },
     "required": [
      "id",
      "providerID"
     ],
     "additionalProperties": false
    },
    "cost": {
     "type": "number"
    },
    "tokens": {
     "type": "object",
     "properties": {
      "input": {
       "type": "..."
      },
      "output": {
       "type": "..."
      },
      "reasoning": {
       "type": "..."
      },
      "cache": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "required": [
      "input",
      "output",
      "reasoning",
      "cache"
     ],
     "additionalProperties": false
    },
    "outcome": {
     "type": "string",
     "enum": [
      "succeeded",
      "failed",
      "interrupted"
     ]
    },
    "time": {
     "type": "object",
     "properties": {
      "created": {
       "type": "..."
      },
      "updated": {
       "type": "..."
      },
      "idle": {
       "type": "..."
      },
      "viewed": {
       "type": "..."
      },
      "archived": {
       "type": "..."
      }
     },
     "required": [
      "created",
      "updated"
     ],
     "additionalProperties": false
    },
    "title": {
     "type": "string"
    },
    "subpath": {
     "type": "string"
    },
    "metadata": {
     "type": "object"
    },
    "permissions": {
     "type": "array",
     "items": {
      "type": "object",
      "properties": {
       "action": "...",
       "resource": "...",
       "effect": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    "revert": {
     "type": "object",
     "properties": {
      "messageID": {
       "type": "...",
       "pattern": "..."
      },
      "partID": {
       "type": "..."
      },
      "snapshot": {
       "type": "..."
      },
      "files": {
       "type": "...",
       "items": "..."
      }
     },
     "required": [
      "messageID"
     ],
     "additionalProperties": false
    },
    "location": {
     "type": "object",
     "properties": {
      "directory": {
       "type": "..."
      }
     },
     "required": [
      "directory"
     ],
     "additionalProperties": false
    }
   },
   "required": [
    "id",
    "projectID",
    "cost",
    "tokens",
    "time",
    "location"
   ],
   "additionalProperties": false
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/session/{sessionID}`

**用途**：单会话详情

**摘要**：Get session


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "object",
   "properties": {
    "id": {
     "type": "string",
     "pattern": "^ses"
    },
    "parentID": {
     "type": "string",
     "pattern": "^ses"
    },
    "fork": {
     "type": "object",
     "properties": {
      "sessionID": {
       "type": "...",
       "pattern": "..."
      },
      "boundary": {
       "anyOf": "..."
      }
     },
     "required": [
      "sessionID",
      "boundary"
     ],
     "additionalProperties": false
    },
    "projectID": {
     "type": "string"
    },
    "agent": {
     "type": "string"
    },
    "model": {
     "type": "object",
     "properties": {
      "id": {
       "type": "..."
      },
      "providerID": {
       "type": "..."
      },
      "variant": {
       "type": "..."
      }
     },
     "required": [
      "id",
      "providerID"
     ],
     "additionalProperties": false
    },
    "cost": {
     "type": "number"
    },
    "tokens": {
     "type": "object",
     "properties": {
      "input": {
       "type": "..."
      },
      "output": {
       "type": "..."
      },
      "reasoning": {
       "type": "..."
      },
      "cache": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "required": [
      "input",
      "output",
      "reasoning",
      "cache"
     ],
     "additionalProperties": false
    },
    "outcome": {
     "type": "string",
     "enum": [
      "succeeded",
      "failed",
      "interrupted"
     ]
    },
    "time": {
     "type": "object",
     "properties": {
      "created": {
       "type": "..."
      },
      "updated": {
       "type": "..."
      },
      "idle": {
       "type": "..."
      },
      "viewed": {
       "type": "..."
      },
      "archived": {
       "type": "..."
      }
     },
     "required": [
      "created",
      "updated"
     ],
     "additionalProperties": false
    },
    "title": {
     "type": "string"
    },
    "subpath": {
     "type": "string"
    },
    "metadata": {
     "type": "object"
    },
    "permissions": {
     "type": "array",
     "items": {
      "type": "object",
      "properties": {
       "action": "...",
       "resource": "...",
       "effect": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    "revert": {
     "type": "object",
     "properties": {
      "messageID": {
       "type": "...",
       "pattern": "..."
      },
      "partID": {
       "type": "..."
      },
      "snapshot": {
       "type": "..."
      },
      "files": {
       "type": "...",
       "items": "..."
      }
     },
     "required": [
      "messageID"
     ],
     "additionalProperties": false
    },
    "location": {
     "type": "object",
     "properties": {
      "directory": {
       "type": "..."
      }
     },
     "required": [
      "directory"
     ],
     "additionalProperties": false
    }
   },
   "required": [
    "id",
    "projectID",
    "cost",
    "tokens",
    "time",
    "location"
   ],
   "additionalProperties": false
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `PATCH /api/session/{sessionID}`

**用途**：改标题

**摘要**：Update session


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}

**请求体**：
```
{
 "type": "object",
 "properties": {
  "title": {
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  },
  "permissions": {
   "anyOf": [
    {
     "type": "array",
     "items": {
      "type": "object",
      "properties": {
       "action": "...",
       "resource": "...",
       "effect": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "additionalProperties": false
}
```

---

## `DELETE /api/session/{sessionID}`

**用途**：删会话

**摘要**：Delete session


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}

---

## `GET /api/session/active`

**用途**：活跃会话（可能可当状态源）

**摘要**：List active sessions


**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "object",
   "patternProperties": {
    "^ses": {
     "type": "object",
     "properties": {
      "type": {
       "type": "...",
       "enum": "..."
      }
     },
     "required": [
      "type"
     ],
     "additionalProperties": false
    }
   }
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/session/{sessionID}/message`

**用途**：会话消息列表

**摘要**：Get session messages


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}
- `limit` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Maximum number of messages to return. When omitted, the endpoint returns its default page size."}
- `order` (query, 可选): {"anyOf": [{"type": "string", "enum": ["...", "..."]}, {"type": "null"}], "description": "Message order for the first page. Use desc for newest first or asc for oldest first."}
- `cursor` (query, 可选): {"anyOf": [{"type": "string", "description": "Opaque pagination cursor returned as cursor.previous or cursor.next in the previous response. Do not combine with order."}, {"type": "null"}]}
- `type` (query, 可选): {"anyOf": [{"type": "string", "enum": ["...", "...", "...", "...", "...", "...", "...", "...", "...", "..."]}, {"type": "null"}], "description": "Filter by message type before pagination. When omitted, all message types are returned. Pass the same type when following cursors."}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "array",
   "items": {
    "anyOf": [
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "agent": "...",
       "previous": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "model": "...",
       "previous": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "projectID": "...",
       "subpath": "...",
       "location": "...",
       "previous": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "text": "...",
       "files": "...",
       "agents": "...",
       "skills": "...",
       "type": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "text": "...",
       "description": {
        "type": "string"
       },
       "type": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "text": "...",
       "description": {
        "type": "string"
       }
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "skill": "...",
       "name": "...",
       "text": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "shellID": "...",
       "command": "...",
       "status": "...",
       "exit": "...",
       "output": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "agent": "...",
       "model": "...",
       "content": "...",
       "snapshot": "...",
       "finish": "...",
       "rawFinish": "...",
       "providerState": "...",
       "cost": "...",
       "tokens": "...",
       "error": "...",
       "retry": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "anyOf": [
       "...",
       "...",
       "..."
      ]
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "outcome": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    ]
   }
  },
  "cursor": {
   "type": "object",
   "properties": {
    "previous": {
     "anyOf": [
      {
       "type": "..."
      },
      {
       "type": "..."
      }
     ]
    },
    "next": {
     "anyOf": [
      {
       "type": "..."
      },
      {
       "type": "..."
      }
     ]
    }
   },
   "additionalProperties": false
  }
 },
 "required": [
  "data",
  "cursor"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/session/{sessionID}/message/{messageID}`

**用途**：单条消息

**摘要**：Get session message


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}
- `messageID` (path, 必填): {"type": "string", "pattern": "^msg_"}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "anyOf": [
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      },
      "agent": {
       "type": "..."
      },
      "previous": {
       "type": "..."
      }
     },
     "required": [
      "id",
      "time",
      "type",
      "agent"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      },
      "model": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "previous": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "required": [
      "id",
      "time",
      "type",
      "model"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      },
      "projectID": {
       "type": "..."
      },
      "subpath": {
       "type": "..."
      },
      "location": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "previous": {
       "anyOf": "..."
      }
     },
     "required": [
      "id",
      "time",
      "type",
      "location"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "text": {
       "type": "..."
      },
      "files": {
       "type": "...",
       "items": "..."
      },
      "agents": {
       "type": "...",
       "items": "..."
      },
      "skills": {
       "type": "...",
       "items": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      }
     },
     "required": [
      "id",
      "time",
      "text",
      "type"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "text": {
       "type": "..."
      },
      "description": {
       "type": "string"
      },
      "type": {
       "type": "...",
       "enum": "..."
      }
     },
     "required": [
      "id",
      "time",
      "text",
      "type"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      },
      "text": {
       "type": "..."
      },
      "description": {
       "type": "string"
      }
     },
     "required": [
      "id",
      "time",
      "type",
      "text"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      },
      "skill": {
       "type": "..."
      },
      "name": {
       "type": "..."
      },
      "text": {
       "type": "..."
      }
     },
     "required": [
      "id",
      "time",
      "type",
      "skill",
      "name",
      "text"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "type": {
       "type": "...",
       "enum": "..."
      },
      "shellID": {
       "type": "...",
       "pattern": "..."
      },
      "command": {
       "type": "..."
      },
      "status": {
       "type": "...",
       "enum": "..."
      },
      "exit": {
       "anyOf": "..."
      },
      "output": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "required": [
      "id",
      "time",
      "type",
      "shellID",
      "command",
      "status"
     ],
     "additionalProperties": false
    },
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "...",
       "pattern": "..."
      },
      "metadata": {
       "type": "..."
      },
      "time"
```

---

## `POST /api/session/{sessionID}/prompt`

**用途**：下发指令（代替 v1 prompt_async）

**摘要**：Send message


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}

**请求体**：
```
{
 "type": "object",
 "properties": {
  "id": {
   "anyOf": [
    {
     "type": "string",
     "pattern": "^msg_"
    },
    {
     "type": "null"
    }
   ]
  },
  "text": {
   "type": "string"
  },
  "files": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "uri": {
      "type": "string"
     },
     "name": {
      "type": "string"
     },
     "description": {
      "type": "string"
     },
     "mention": {
      "type": "object",
      "properties": {
       "start": "...",
       "end": "...",
       "text": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    "required": [
     "uri"
    ],
    "additionalProperties": false
   }
  },
  "agents": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "name": {
      "type": "string"
     },
     "mention": {
      "type": "object",
      "properties": {
       "start": "...",
       "end": "...",
       "text": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    "required": [
     "name"
    ],
    "additionalProperties": false
   }
  },
  "skills": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string"
     },
     "mention": {
      "type": "object",
      "properties": {
       "start": "...",
       "end": "...",
       "text": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    "required": [
     "id"
    ],
    "additionalProperties": false
   }
  },
  "metadata": {
   "type": "object"
  },
  "delivery": {
   "anyOf": [
    {
     "type": "string",
     "enum": [
      "steer",
      "queue"
     ]
    },
    {
     "type": "null"
    }
   ]
  },
  "resume": {
   "anyOf": [
    {
     "type": "boolean"
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "required": [
  "text"
 ],
 "additionalProperties": false
}
```

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "object",
   "properties": {
    "id": {
     "type": "string",
     "pattern": "^msg_"
    },
    "sessionID": {
     "type": "string",
     "pattern": "^ses"
    },
    "time": {
     "type": "object",
     "properties": {
      "created": {
       "type": "..."
      }
     },
     "required": [
      "created"
     ],
     "additionalProperties": false
    },
    "type": {
     "type": "string",
     "enum": [
      "user"
     ]
    },
    "payload": {
     "type": "object",
     "properties": {
      "text": {
       "type": "..."
      },
      "files": {
       "type": "...",
       "items": "..."
      },
      "agents": {
       "type": "...",
       "items": "..."
      },
      "skills": {
       "type": "...",
       "items": "..."
      },
      "metadata": {
       "type": "..."
      }
     },
     "required": [
      "text"
     ],
     "additionalProperties": false
    },
    "delivery": {
     "type": "string",
     "enum": [
      "steer",
      "queue"
     ]
    }
   },
   "required": [
    "id",
    "sessionID",
    "time",
    "type",
    "payload",
    "delivery"
   ],
   "additionalProperties": false
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `POST /api/session/{sessionID}/interrupt`

**用途**：中止（代替 v1 abort）

**摘要**：Interrupt session execution


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}
- `resume` (query, 可选): {"anyOf": [{"type": "string", "enum": ["...", "..."]}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "interrupted": {
   "type": "boolean",
   "description": "Whether an active execution owned by this OpenCode process was interrupted."
  }
 },
 "required": [
  "interrupted"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/session/{sessionID}/diff`

**用途**：会话内文件改动

**摘要**：Diff session turns


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}
- `from` (query, 可选): {"anyOf": [{"type": "string", "pattern": "^msg_"}, {"type": "null"}], "description": "User message whose turn to diff. Defaults to the turn of the newest user message."}
- `to` (query, 可选): {"anyOf": [{"type": "string", "pattern": "^msg_"}, {"type": "null"}], "description": "Later user message whose turn ends the range. Defaults to the turn of `from` alone."}
- `context` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Unchanged lines around each hunk. Omit for full-file patches."}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "file": {
      "type": "string"
     },
     "patch": {
      "type": "string"
     },
     "additions": {
      "type": "integer",
      "minimum": 0
     },
     "deletions": {
      "type": "integer",
      "minimum": 0
     },
     "status": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "..."
      ]
     }
    },
    "required": [
     "file",
     "patch",
     "additions",
     "deletions",
     "status"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/session/{sessionID}/context`

**用途**：会话上下文（可能含 todo/状态）

**摘要**：Get session context


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "array",
   "items": {
    "anyOf": [
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "agent": "...",
       "previous": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "model": "...",
       "previous": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "projectID": "...",
       "subpath": "...",
       "location": "...",
       "previous": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "text": "...",
       "files": "...",
       "agents": "...",
       "skills": "...",
       "type": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "text": "...",
       "description": {
        "type": "string"
       },
       "type": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "text": "...",
       "description": {
        "type": "string"
       }
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "skill": "...",
       "name": "...",
       "text": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "shellID": "...",
       "command": "...",
       "status": "...",
       "exit": "...",
       "output": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "agent": "...",
       "model": "...",
       "content": "...",
       "snapshot": "...",
       "finish": "...",
       "rawFinish": "...",
       "providerState": "...",
       "cost": "...",
       "tokens": "...",
       "error": "...",
       "retry": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     {
      "anyOf": [
       "...",
       "...",
       "..."
      ]
     },
     {
      "type": "object",
      "properties": {
       "id": "...",
       "metadata": "...",
       "time": "...",
       "type": "...",
       "outcome": "..."
      },
      "required": [
       "...",
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    ]
   }
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/session/{sessionID}/permission`

**用途**：待审批列表

**摘要**：List session permission requests


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string",
      "pattern": "^per"
     },
     "sessionID": {
      "type": "string",
      "pattern": "^ses"
     },
     "action": {
      "type": "string"
     },
     "resources": {
      "type": "array",
      "items": {
       "type": "..."
      }
     },
     "save": {
      "type": "array",
      "items": {
       "type": "..."
      }
     },
     "metadata": {
      "type": "object"
     },
     "source": {
      "anyOf": [
       "..."
      ]
     },
     "message": {
      "type": "string"
     }
    },
    "required": [
     "id",
     "sessionID",
     "action",
     "resources"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "data"
 ],
 "additionalProperties": false
}
```

---

## `POST /api/session/{sessionID}/permission/{requestID}/reply`

**用途**：权限审批回复

**摘要**：Reply to pending permission request


**参数**：
- `sessionID` (path, 必填): {"type": "string", "pattern": "^ses"}
- `requestID` (path, 必填): {"type": "string", "pattern": "^per"}

**请求体**：
```
{
 "type": "object",
 "properties": {
  "decision": {
   "type": "string",
   "enum": [
    "once",
    "always",
    "reject"
   ]
  },
  "message": {
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "required": [
  "decision"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/permission/request`

**用途**：全局待审批

**摘要**：List pending permission requests


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string",
      "pattern": "^per"
     },
     "sessionID": {
      "type": "string",
      "pattern": "^ses"
     },
     "action": {
      "type": "string"
     },
     "resources": {
      "type": "array",
      "items": {
       "type": "..."
      }
     },
     "save": {
      "type": "array",
      "items": {
       "type": "..."
      }
     },
     "metadata": {
      "type": "object"
     },
     "source": {
      "anyOf": [
       "..."
      ]
     },
     "message": {
      "type": "string"
     }
    },
    "required": [
     "id",
     "sessionID",
     "action",
     "resources"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/fs/list`

**用途**：目录列表（代替 v1 /file）

**摘要**：List directory


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}
- `path` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "An absolute path or a path relative to the requested location. Defaults to the location directory."}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "path": {
      "type": "string"
     },
     "type": {
      "type": "string",
      "enum": [
       "...",
       "..."
      ]
     }
    },
    "required": [
     "path",
     "type"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/fs/read/*`

**用途**：读文件内容（代替 v1 /file/content）

**摘要**：Read file


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
null
```

---

## `GET /api/fs/find`

**用途**：文件搜索（代替 v1 /find/file）

**摘要**：Find files


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}
- `query` (query, 必填): {"type": "string"}
- `type` (query, 可选): {"type": "string", "enum": ["file", "directory"]}
- `limit` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "path": {
      "type": "string"
     },
     "type": {
      "type": "string",
      "enum": [
       "...",
       "..."
      ]
     }
    },
    "required": [
     "path",
     "type"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/vcs/status`

**用途**：工作区改动状态

**摘要**：VCS status


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "file": {
      "type": "string"
     },
     "additions": {
      "type": "integer",
      "minimum": 0
     },
     "deletions": {
      "type": "integer",
      "minimum": 0
     },
     "status": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "..."
      ]
     }
    },
    "required": [
     "file",
     "additions",
     "deletions",
     "status"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/vcs/diff`

**用途**：VCS diff

**摘要**：VCS diff


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}
- `mode` (query, 必填): {"type": "string", "enum": ["working", "branch", "committed"]}
- `base` (query, 可选): {"type": "string"}
- `context` (query, 可选): {"anyOf": [{"type": "string"}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "file": {
      "type": "string"
     },
     "patch": {
      "type": "string"
     },
     "additions": {
      "type": "integer",
      "minimum": 0
     },
     "deletions": {
      "type": "integer",
      "minimum": 0
     },
     "status": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "..."
      ]
     }
    },
    "required": [
     "file",
     "patch",
     "additions",
     "deletions",
     "status"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/model`

**用途**：模型列表

**摘要**：List models


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string"
     },
     "modelID": {
      "type": "string"
     },
     "providerID": {
      "type": "string"
     },
     "canonical": {
      "type": "string"
     },
     "family": {
      "type": "string"
     },
     "name": {
      "type": "string"
     },
     "compatibility": {
      "type": "object",
      "properties": {
       "reasoningField": "...",
       "requireReasoning": "...",
       "maxTokensField": "...",
       "requireFinishReason": "...",
       "requireAssistantAfterTool": "...",
       "supportsPromptCacheKey": "..."
      },
      "additionalProperties": false
     },
     "package": {
      "type": "string"
     },
     "settings": {
      "type": "object",
      "properties": {
       "compaction": "..."
      },
      "allOf": [
       "..."
      ]
     },
     "headers": {
      "type": "object",
      "additionalProperties": {
       "type": "..."
      }
     },
     "body": {
      "type": "object"
     },
     "capabilities": {
      "type": "object",
      "properties": {
       "tools": "...",
       "input": "...",
       "output": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "variants": {
      "type": "array",
      "items": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "time": {
      "type": "object",
      "properties": {
       "released": "..."
      },
      "required": [
       "..."
      ],
      "additionalProperties": false
     },
     "cost": {
      "type": "array",
      "items": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "status": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "...",
       "..."
      ]
     },
     "enabled": {
      "type": "boolean"
     },
     "limit": {
      "type": "object",
      "properties": {
       "context": "...",
       "input": "...",
       "output": "..."
      },
      "required": [
       "...",
       "..."
      ],
      "additionalProperties": false
     }
    },
    "required": [
     "id",
     "modelID",
     "providerID",
     "name",
     "capabilities",
     "variants",
     "time",
     "cost",
     "status",
     "enabled",
     "limit"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/model/default`

**用途**：默认模型

**摘要**：Get default model


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "anyOf": [
    {
     "type": "object",
     "properties": {
      "id": {
       "type": "..."
      },
      "modelID": {
       "type": "..."
      },
      "providerID": {
       "type": "..."
      },
      "canonical": {
       "type": "..."
      },
      "family": {
       "type": "..."
      },
      "name": {
       "type": "..."
      },
      "compatibility": {
       "type": "...",
       "properties": "...",
       "additionalProperties": "..."
      },
      "package": {
       "type": "..."
      },
      "settings": {
       "type": "...",
       "properties": "...",
       "allOf": "..."
      },
      "headers": {
       "type": "...",
       "additionalProperties": "..."
      },
      "body": {
       "type": "..."
      },
      "capabilities": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "variants": {
       "type": "...",
       "items": "..."
      },
      "time": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      },
      "cost": {
       "type": "...",
       "items": "..."
      },
      "status": {
       "type": "...",
       "enum": "..."
      },
      "enabled": {
       "type": "..."
      },
      "limit": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     },
     "required": [
      "id",
      "modelID",
      "providerID",
      "name",
      "capabilities",
      "variants",
      "time",
      "cost",
      "status",
      "enabled",
      "limit"
     ],
     "additionalProperties": false
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/provider`

**用途**：provider 列表

**摘要**：List providers


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string"
     },
     "canonical": {
      "type": "string"
     },
     "integrationID": {
      "type": "string"
     },
     "name": {
      "type": "string"
     },
     "activation": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "..."
      ]
     },
     "package": {
      "type": "string"
     },
     "settings": {
      "type": "object",
      "properties": {
       "timeout": "...",
       "chunkTimeout": "...",
       "compaction": "...",
       "transport": "..."
      },
      "allOf": [
       "..."
      ]
     },
     "headers": {
      "type": "object",
      "additionalProperties": {
       "type": "..."
      }
     },
     "body": {
      "type": "object"
     }
    },
    "required": [
     "id",
     "name",
     "activation",
     "package"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/event`

**用途**：SSE 事件流（代替 v1 /global/event）

**摘要**：Subscribe to events


**200 响应**：
```
null
```

---

## `GET /api/agent`

**用途**：agent 列表

**摘要**：List agents


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "id": {
      "type": "string"
     },
     "name": {
      "type": "string"
     },
     "model": {
      "type": "object",
      "properties": {
       "id": "...",
       "providerID": "...",
       "variant": "..."
      },
      "required": [
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "request": {
      "type": "object",
      "properties": {
       "settings": "...",
       "headers": "...",
       "body": "..."
      },
      "required": [
       "...",
       "...",
       "..."
      ],
      "additionalProperties": false
     },
     "system": {
      "type": "string"
     },
     "description": {
      "type": "string"
     },
     "mode": {
      "type": "string",
      "enum": [
       "...",
       "...",
       "..."
      ]
     },
     "hidden": {
      "type": "boolean"
     },
     "color": {
      "type": "string"
     },
     "steps": {
      "type": "integer",
      "exclusiveMinimum": 0
     },
     "permissions": {
      "type": "array",
      "items": {
       "type": "...",
       "properties": "...",
       "required": "...",
       "additionalProperties": "..."
      }
     }
    },
    "required": [
     "id",
     "name",
     "request",
     "mode",
     "hidden",
     "permissions"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```

---

## `GET /api/command`

**用途**：命令列表

**摘要**：List commands


**参数**：
- `location` (query, 可选): {"anyOf": [{"type": "object", "properties": {"directory": "..."}, "additionalProperties": false}, {"type": "null"}]}

**200 响应**：
```
{
 "type": "object",
 "properties": {
  "location": {
   "type": "object",
   "properties": {
    "directory": {
     "type": "string"
    }
   },
   "required": [
    "directory"
   ],
   "additionalProperties": false
  },
  "data": {
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "name": {
      "type": "string"
     },
     "description": {
      "type": "string"
     }
    },
    "required": [
     "name"
    ],
    "additionalProperties": false
   }
  }
 },
 "required": [
  "location",
  "data"
 ],
 "additionalProperties": false
}
```