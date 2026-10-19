import { initializeApp } from "firebase-admin/app";
import { setGlobalOptions } from "firebase-functions/v2";
import { REGION } from "./config.js";

// Imported first by index.ts. ES modules run their imports before the importing module's body,
// so global options set in index.ts itself would come after every function was defined (they
// then default to us-central1). test/regions.test.ts checks every export.
initializeApp();
setGlobalOptions({ region: REGION, maxInstances: 10 });
