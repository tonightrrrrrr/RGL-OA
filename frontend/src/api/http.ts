import axios from 'axios'

/** 后端统一返回格式：code 为 0 表示成功 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  constructor(public code: number, message: string) {
    super(message)
  }
}

const http = axios.create({ baseURL: '/api', timeout: 120000 })

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>
    // 文件下载等非 JSON 响应直接返回
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return resp
    }
    if (body.code !== 0) {
      return Promise.reject(new ApiError(body.code, body.message))
    }
    resp.data = body.data
    return resp
  },
  (err) => {
    const body = err.response?.data as ApiResponse<unknown> | undefined
    if (body && typeof body === 'object' && 'message' in body) {
      return Promise.reject(new ApiError(body.code, body.message))
    }
    return Promise.reject(new ApiError(-1, '无法连接到程序后台，请确认程序仍在运行'))
  }
)

export async function get<T>(url: string, params?: object): Promise<T> {
  return (await http.get<T>(url, { params })).data
}

export async function post<T>(url: string, body?: unknown, params?: object): Promise<T> {
  return (await http.post<T>(url, body, { params })).data
}

export async function put<T>(url: string, body?: unknown): Promise<T> {
  return (await http.put<T>(url, body)).data
}

export async function del<T>(url: string): Promise<T> {
  return (await http.delete<T>(url)).data
}

export async function postForm<T>(url: string, form: FormData): Promise<T> {
  return (await http.post<T>(url, form, { timeout: 0 })).data
}

/** 多文件上传：文件字段名为 files，其余字段按表单字段提交 */
export async function upload<T>(url: string, files: File[], extra?: Record<string, string>): Promise<T> {
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  if (extra) {
    Object.entries(extra).forEach(([k, v]) => form.append(k, v))
  }
  return postForm<T>(url, form)
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
