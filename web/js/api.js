// HTTP access to the local SLT application (which relays the SLT server).

async function request(method, url, body) {
  const init = { method, headers: { 'X-SLT': '1' } };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw new Error("Cable Club ne répond plus. L'application est-elle toujours ouverte ?");
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }
  if (!res.ok) {
    const err = new Error(data && data.error ? data.error : `Erreur ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

/** The local application. */
export const local = {
  get: (u) => request('GET', u),
  post: (u, b) => request('POST', u, b ?? {}),
};

/** The SLT server, through the local application (the session token stays there). */
export const srv = {
  get: (p) => request('GET', `/api/srv${p}`),
  post: (p, b) => request('POST', `/api/srv${p}`, b ?? {}),
};

export async function pollJob(jobId, onUpdate) {
  for (;;) {
    const s = await local.get(`/api/jobs/${jobId}`);
    if (onUpdate) onUpdate(s);
    if (s.state === 'done') return s.result;
    if (s.state === 'error') throw new Error(s.error || 'La tâche a échoué');
    await new Promise((r) => setTimeout(r, 400));
  }
}
