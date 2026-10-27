import "./style.css";
import { createApp, render } from "./app";

const root = document.getElementById("app");
if (root) void render(createApp(root));
