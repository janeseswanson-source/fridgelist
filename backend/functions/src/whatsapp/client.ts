/** Sending replies and fetching voice notes (WhatsApp Cloud API). */
export interface WhatsAppClient {
  sendText(phoneNumberId: string, to: string, text: string): Promise<void>;
  downloadMedia(mediaId: string): Promise<{ bytes: Buffer; mimeType: string }>;
}

/** Check the current Graph API version when phase 2 goes live. */
export const GRAPH_VERSION = "v23.0";

export class GraphWhatsAppClient implements WhatsAppClient {
  constructor(private readonly token: string) {}

  private async graph(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await fetch(`https://graph.facebook.com/${GRAPH_VERSION}/${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${this.token}`, "Content-Type": "application/json" },
    });
    if (!response.ok) throw new Error(`graph ${String(response.status)}`);
    return response;
  }

  async sendText(phoneNumberId: string, to: string, text: string): Promise<void> {
    await this.graph(`${phoneNumberId}/messages`, {
      method: "POST",
      body: JSON.stringify({ messaging_product: "whatsapp", to, type: "text", text: { body: text } }),
    });
  }

  async downloadMedia(mediaId: string): Promise<{ bytes: Buffer; mimeType: string }> {
    const meta = (await (await this.graph(mediaId)).json()) as { url: string; mime_type?: string };
    const file = await fetch(meta.url, { headers: { Authorization: `Bearer ${this.token}` } });
    if (!file.ok) throw new Error(`media ${String(file.status)}`);
    return { bytes: Buffer.from(await file.arrayBuffer()), mimeType: meta.mime_type ?? "audio/ogg" };
  }
}
