import { API_ERROR_STATUS, type ApiError, type ApiErrorCode } from "@overdrive/contracts";
import type { FastifyError, FastifyInstance, FastifyReply } from "fastify";

/** Thrown by a handler to answer with an error body: the status follows from the code. */
export class HttpError extends Error {
  readonly code: ApiErrorCode;

  constructor(code: ApiErrorCode, message: string) {
    super(message);
    this.name = "HttpError";
    this.code = code;
  }

  get statusCode(): number {
    return API_ERROR_STATUS[this.code];
  }
}

export function errorBody(code: ApiErrorCode, message: string): ApiError {
  return { error: { code, message } };
}

export function sendError(reply: FastifyReply, code: ApiErrorCode, message: string): FastifyReply {
  return reply.code(API_ERROR_STATUS[code]).send(errorBody(code, message));
}

/**
 * Makes every failure, including the ones Fastify raises itself (malformed JSON, a body over the
 * limit, an unknown content type, an unknown route), answer with the error body of the contract.
 */
export function installErrorHandling(app: FastifyInstance): void {
  app.setNotFoundHandler((_request, reply) => sendError(reply, "not_found", "No such endpoint"));

  app.setErrorHandler((error: FastifyError | HttpError, request, reply) => {
    if (error instanceof HttpError) return sendError(reply, error.code, error.message);
    switch (error.code) {
      case "FST_ERR_CTP_BODY_TOO_LARGE":
        return sendError(reply, "payload_too_large", "The request body is too large");
      case "FST_ERR_CTP_INVALID_MEDIA_TYPE":
        return sendError(reply, "unsupported_media_type", error.message);
      case "FST_ERR_CTP_INVALID_JSON_BODY":
      case "FST_ERR_CTP_EMPTY_JSON_BODY":
      case "FST_ERR_CTP_INVALID_CONTENT_LENGTH":
      case "FST_ERR_CTP_INVALID_HANDLER":
        return sendError(reply, "invalid_json", error.message);
    }
    if (error.statusCode === 400) return sendError(reply, "invalid_json", error.message);
    request.log.error({ err: error }, "unhandled error");
    return sendError(reply, "internal_error", "Something went wrong on the server");
  });
}
