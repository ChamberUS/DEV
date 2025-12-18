export class HttpError extends Error {
  status: number;
  body: unknown;

  constructor(message: string, options: { status: number; body?: unknown }) {
    super(message);
    this.name = "HttpError";
    this.status = options.status;
    this.body = options.body;
  }
}

type HttpClientOptions = {
  baseUrl?: string;
};

function resolveBaseUrl(): string {
  const baseUrl = import.meta.env.VITE_API_BASE_URL;
  if (typeof baseUrl !== "string") return "";
  return baseUrl.replace(/\/+$/, "");
}

function buildUrl(baseUrl: string, path: string): string {
  if (!baseUrl) return path;
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  if (!path.startsWith("/")) return `${baseUrl}/${path}`;
  return `${baseUrl}${path}`;
}

async function parseBody(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }
  try {
    return await response.text();
  } catch {
    return null;
  }
}

export function createHttpClient(options: HttpClientOptions = {}) {
  const baseUrl = options.baseUrl ?? resolveBaseUrl();

  return {
    async get<T>(path: string, init?: RequestInit): Promise<T> {
      const response = await fetch(buildUrl(baseUrl, path), {
        ...init,
        method: "GET",
        headers: {
          Accept: "application/json",
          ...(init?.headers ?? {}),
        },
      });

      const body = await parseBody(response);
      if (!response.ok) throw new HttpError("Request failed", { status: response.status, body });
      return body as T;
    },

    async post<T>(path: string, json?: unknown, init?: RequestInit): Promise<T> {
      const response = await fetch(buildUrl(baseUrl, path), {
        ...init,
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          ...(init?.headers ?? {}),
        },
        body: json === undefined ? undefined : JSON.stringify(json),
      });

      const body = await parseBody(response);
      if (!response.ok) throw new HttpError("Request failed", { status: response.status, body });
      return body as T;
    },
  };
}

export const httpClient = createHttpClient();

