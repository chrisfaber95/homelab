import { mountSsh } from "./pages/ssh";

const app = document.getElementById("app");
const pages: Record<string, (root: HTMLElement) => Promise<void>> = { "/ssh": mountSsh };
const mount = pages[location.pathname];
if (app && mount) void mount(app);
