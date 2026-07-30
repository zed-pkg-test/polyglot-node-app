// npm owns everything else in this project; zed sources exactly one dependency
// -- the Node slice of polyglot-lib -- and links it where Node already looks.
const lib = require("@zedtest/polyglot-lib-nodejs");

if (lib.LANGUAGE !== "nodejs") {
  throw new Error(`expected the nodejs slice, got ${lib.LANGUAGE}`);
}
console.log(lib.greet("node-app"));
