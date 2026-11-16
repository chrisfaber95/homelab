import { greeting } from "./greeting";

const app = document.getElementById("app");
if (app) app.textContent = greeting("scaffold");
