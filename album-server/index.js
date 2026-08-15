"use strict";
const app = require("./src/app");

const PORT = process.env.PORT || 8096;
app.listen(PORT, () => {
  console.log(`Album server (Express + SQLite) listening on :${PORT}`);
});
