/**
 * Guest, join and list pages (ARCHITECTURE §3.7). Plain DOM, no framework, no trackers.
 * User content (household, list and item names) is only ever set with textContent.
 * Callables are reached same-origin through Hosting rewrites (/api/<name>).
 */
type Lang = "ar" | "tr" | "en";

interface Config {
  linkDomain: string;
  playStoreUrl: string;
  appStoreUrl: string;
  appCheckSiteKey: string;
}

interface GuestItem {
  id: string;
  title: string;
  subtitle?: string;
  category: string;
  status: "open" | "claimed";
}

interface Resolved {
  householdName: string;
  listName: string;
  scope: "view" | "add";
  items: GuestItem[];
}

const TEXT: Record<Lang, Record<string, string>> = {
  en: {
    loading: "Loading…",
    gone: "This link no longer works.",
    error: "Something went wrong. Check your connection and try again.",
    empty: "Nothing on the list right now.",
    someone: "Someone is getting this",
    copy: "Copy list",
    copied: "Copied",
    share: "Share",
    addTitle: "Add to this list",
    addHint: "e.g. milk, 2 bread",
    yourName: "Your name (optional)",
    add: "Add",
    added: "Added. Thank you!",
    tooMany: "Too many additions from this link. Please try again later.",
    getApp: "Get FridgeList",
    soon: "Coming soon to the App Store and Google Play.",
    joinTitle: "You're invited to a household list",
    joinSteps: "Install FridgeList, then open this link again on your phone, or enter this code in the app:",
    copyCode: "Copy code",
    listTitle: "This list is in FridgeList",
    listSteps: "Open it in the app. If you don't have it yet:",
    osm: "Store data © OpenStreetMap contributors",
  },
  ar: {
    loading: "جارٍ التحميل…",
    gone: "هذا الرابط لم يعد يعمل.",
    error: "حدث خطأ. تحقق من الاتصال وحاول مجدداً.",
    empty: "لا شيء في القائمة الآن.",
    someone: "أحدهم سيحضره",
    copy: "نسخ القائمة",
    copied: "تم النسخ",
    share: "مشاركة",
    addTitle: "أضف إلى هذه القائمة",
    addHint: "مثلاً: حليب، ٢ خبز",
    yourName: "اسمك (اختياري)",
    add: "إضافة",
    added: "تمت الإضافة. شكراً!",
    tooMany: "إضافات كثيرة من هذا الرابط. حاول لاحقاً.",
    getApp: "احصل على FridgeList",
    soon: "قريباً على App Store و Google Play.",
    joinTitle: "أنت مدعو إلى قائمة أسرة",
    joinSteps: "ثبّت FridgeList ثم افتح هذا الرابط مجدداً على هاتفك، أو أدخل هذا الرمز في التطبيق:",
    copyCode: "نسخ الرمز",
    listTitle: "هذه القائمة في FridgeList",
    listSteps: "افتحها في التطبيق. إن لم يكن لديك:",
    osm: "بيانات المتاجر © مساهمو OpenStreetMap",
  },
  tr: {
    loading: "Yükleniyor…",
    gone: "Bu bağlantı artık çalışmıyor.",
    error: "Bir sorun oluştu. Bağlantını kontrol edip tekrar dene.",
    empty: "Şu an listede bir şey yok.",
    someone: "Biri alıyor",
    copy: "Listeyi kopyala",
    copied: "Kopyalandı",
    share: "Paylaş",
    addTitle: "Bu listeye ekle",
    addHint: "ör. süt, 2 ekmek",
    yourName: "Adın (isteğe bağlı)",
    add: "Ekle",
    added: "Eklendi. Teşekkürler!",
    tooMany: "Bu bağlantıdan çok fazla ekleme yapıldı. Lütfen sonra tekrar dene.",
    getApp: "FridgeList'i indir",
    soon: "Yakında App Store ve Google Play'de.",
    joinTitle: "Bir ev listesine davet edildin",
    joinSteps: "FridgeList'i yükle, sonra bu bağlantıyı telefonunda tekrar aç ya da bu kodu uygulamaya gir:",
    copyCode: "Kodu kopyala",
    listTitle: "Bu liste FridgeList'te",
    listSteps: "Uygulamada aç. Henüz yoksa:",
    osm: "Mağaza verileri © OpenStreetMap katkıda bulunanlar",
  },
};

const config = (window as unknown as { FRIDGELIST: Config }).FRIDGELIST;

function pickLang(): Lang {
  const asked = new URLSearchParams(location.search).get("lang");
  for (const tag of [asked, ...navigator.languages]) {
    const primary = tag?.slice(0, 2).toLowerCase();
    if (primary === "ar" || primary === "tr" || primary === "en") return primary;
  }
  return "en";
}

const lang = pickLang();
const t = (key: string): string => TEXT[lang][key] ?? TEXT.en[key] ?? key;
document.documentElement.lang = lang;
document.documentElement.dir = lang === "ar" ? "rtl" : "ltr";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}

const main = document.querySelector("main") as HTMLElement;

class CallError extends Error {
  constructor(readonly status: string) {
    super(status);
  }
}

let appCheckToken: (() => Promise<string>) | null = null;

/** App Check for the public callables, loaded only when a site key is configured. */
async function initAppCheck(): Promise<void> {
  if (!config.appCheckSiteKey) return;
  const v = "13.0.0";
  const appMod = (await import(`https://www.gstatic.com/firebasejs/${v}/firebase-app.js`)) as {
    initializeApp: (c: object) => unknown;
  };
  const checkMod = (await import(`https://www.gstatic.com/firebasejs/${v}/firebase-app-check.js`)) as {
    initializeAppCheck: (app: unknown, o: object) => unknown;
    ReCaptchaEnterpriseProvider: new (key: string) => unknown;
    getToken: (check: unknown) => Promise<{ token: string }>;
  };
  const firebaseConfig = (await (await fetch("/__/firebase/init.json")).json()) as object;
  const check = checkMod.initializeAppCheck(appMod.initializeApp(firebaseConfig), {
    provider: new checkMod.ReCaptchaEnterpriseProvider(config.appCheckSiteKey),
    isTokenAutoRefreshEnabled: true,
  });
  appCheckToken = async () => (await checkMod.getToken(check)).token;
}

/** The callable protocol over fetch: POST {data} → {result} | {error: {status}}. */
async function call<T>(name: string, data: object): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (appCheckToken) headers["X-Firebase-AppCheck"] = await appCheckToken();
  let response: Response;
  try {
    response = await fetch(`/api/${name}`, { method: "POST", headers, body: JSON.stringify({ data }) });
  } catch {
    throw new CallError("NETWORK");
  }
  const body = (await response.json().catch(() => ({}))) as { result?: T; error?: { status?: string } };
  if (body.error || !response.ok) throw new CallError(body.error?.status ?? "INTERNAL");
  return body.result as T;
}

function badges(): HTMLElement {
  const box = el("section", undefined, "badges");
  box.append(el("h2", t("getApp")));
  const links: [string, string][] = [
    [config.appStoreUrl, "App Store"],
    [config.playStoreUrl, "Google Play"],
  ];
  const real = links.filter(([url]) => url);
  if (real.length === 0) box.append(el("p", t("soon"), "muted"));
  for (const [url, label] of real) {
    const a = el("a", label, "badge");
    a.href = url;
    a.rel = "noopener";
    box.append(a);
  }
  return box;
}

function footer(): HTMLElement {
  const f = el("footer");
  const a = el("a", t("osm"));
  a.href = "https://www.openstreetmap.org/copyright";
  a.rel = "noopener";
  f.append(a);
  return f;
}

function button(text: string, onClick: () => void, cls = "btn"): HTMLButtonElement {
  const b = el("button", text, cls);
  b.type = "button";
  b.addEventListener("click", onClick);
  return b;
}

async function copy(text: string, b: HTMLButtonElement, label: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    b.textContent = t("copied");
    setTimeout(() => (b.textContent = label), 2_000);
  } catch {
    /* clipboard blocked: nothing to do */
  }
}

async function guestPage(token: string): Promise<void> {
  main.replaceChildren(el("p", t("loading"), "muted"));
  let data: Resolved;
  try {
    data = await call<Resolved>("resolveShareLink", { token, lang });
  } catch (e) {
    main.replaceChildren(el("p", (e as CallError).status === "NOT_FOUND" ? t("gone") : t("error"), "notice"), badges());
    return;
  }
  const header = el("header");
  header.append(el("p", data.householdName, "muted"), el("h1", data.listName));
  const list = el("ul", undefined, "items");
  for (const item of data.items) {
    const li = el("li");
    li.append(el("span", item.title, "title"));
    if (item.subtitle) li.append(el("span", item.subtitle, "sub"));
    if (item.status === "claimed") li.append(el("span", t("someone"), "claimed"));
    list.append(li);
  }
  const asText = [data.listName, ...data.items.map((i) => `• ${i.title}${i.subtitle ? ` (${i.subtitle})` : ""}`)].join("\n");
  const actions = el("div", undefined, "actions");
  const copyButton: HTMLButtonElement = button(t("copy"), () => void copy(asText, copyButton, t("copy")));
  actions.append(
    copyButton,
    button(t("share"), () => {
      if (typeof navigator.share === "function") {
        navigator.share({ title: data.listName, text: asText }).catch(() => undefined);
      } else {
        window.open(`https://wa.me/?text=${encodeURIComponent(asText)}`, "_blank", "noopener");
      }
    }, "btn secondary"),
  );
  const parts: HTMLElement[] = [header, data.items.length > 0 ? list : el("p", t("empty"), "muted"), actions];
  if (data.scope === "add") parts.push(addForm(token));
  main.replaceChildren(...parts, badges());
}

function addForm(token: string): HTMLElement {
  const form = el("form", undefined, "add");
  const title = el("h2", t("addTitle"));
  const text = el("input");
  text.maxLength = 200;
  text.required = true;
  text.placeholder = t("addHint");
  text.setAttribute("aria-label", t("addTitle"));
  const name = el("input");
  name.maxLength = 30;
  name.placeholder = t("yourName");
  name.setAttribute("aria-label", t("yourName"));
  const submit = el("button", t("add"), "btn");
  submit.type = "submit";
  const status = el("p", "", "muted");
  status.setAttribute("role", "status");
  form.append(title, text, name, submit, status);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    submit.disabled = true;
    call<{ added: number }>("guestAddItem", { token, text: text.value, ...(name.value ? { guestName: name.value } : {}) })
      .then(() => {
        status.textContent = t("added");
        text.value = "";
        void guestPage(token);
      })
      .catch((e: unknown) => {
        const s = (e as CallError).status;
        status.textContent = s === "RESOURCE_EXHAUSTED" ? t("tooMany") : s === "NOT_FOUND" ? t("gone") : t("error");
      })
      .finally(() => (submit.disabled = false));
  });
  return form;
}

function joinPage(code: string): void {
  const codeBox = el("p", code, "code");
  const copyButton: HTMLButtonElement = button(t("copyCode"), () => void copy(code, copyButton, t("copyCode")));
  main.replaceChildren(el("h1", t("joinTitle")), el("p", t("joinSteps")), codeBox, copyButton, badges());
}

function listPage(): void {
  main.replaceChildren(el("h1", t("listTitle")), el("p", t("listSteps")), badges());
}

async function start(): Promise<void> {
  document.body.append(footer());
  const [, kind, value] = location.pathname.split("/");
  if (kind === "join" && value && /^[A-Za-z0-9-]{1,32}$/.test(value)) return joinPage(value.toUpperCase());
  if (kind === "l") return listPage();
  if (kind === "k" && value) {
    await initAppCheck().catch(() => undefined);
    return guestPage(value);
  }
  listPage();
}

void start();
