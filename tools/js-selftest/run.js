// Behaviour check for the JavaScript GeminiAssist injects into Gemini.
// Run tools/js-selftest/run.sh (it extracts the scripts from MainActivity.java first).
//
// Verifies that:
//   1. the autofocus script focuses the prompt box and opens the native keyboard,
//   2. the ask/share script writes the text into the prompt box (contenteditable),
//      fires the input/change events the SPA needs and opens the keyboard,
//   3. the blob patch keeps blobs in window.blobMap for the download listener,
//   4. the clipboard patch routes copies through the native clipboard.

const fs = require('fs');
const path = require('path');
const assert = require('assert');

const harness = require('./harness.js');
const read = (name) => fs.readFileSync(path.join(__dirname, name), 'utf8');

(async function main() {
    // --- 1. autofocus -----------------------------------------------------
    eval(read('AUTOFOCUS_JS.js'));
    assert.ok(global.calls.includes('focus:DIV'), 'autofocus must focus the prompt box');
    assert.ok(global.calls.includes('Android.showSoftKeyboard'), 'autofocus must open the keyboard');
    console.log('AUTOFOCUS_JS       OK');

    // --- 2. ask / share injection ----------------------------------------
    global.calls.length = 0;
    eval(read('INJECT_PROMPT_JS.js'));
    const el = harness.contentEditable;
    assert.strictEqual(el.textContent, 'sample text', 'shared text must reach the prompt box');
    assert.ok(global.calls.includes('event:input'), 'input event must fire for the SPA');
    assert.ok(global.calls.includes('event:change'), 'change event must fire for the SPA');
    assert.ok(global.calls.includes('Android.showSoftKeyboard'), 'injection must open the keyboard');
    console.log('INJECT_PROMPT_JS   OK');

    // --- 3. blob patch ----------------------------------------------------
    global.calls.length = 0;
    global.URL = { createObjectURL: () => 'blob:stub' };
    global.Blob = class Blob {
        constructor(type) {
            this.type = type || '';
        }
    };
    eval(read('BLOB_PATCH_JS.js'));
    assert.strictEqual(URL.createObjectURL(new Blob('text/markdown')), 'blob:stub');
    assert.strictEqual(window.blobMap.size, 1, 'blob must be remembered for the download listener');
    console.log('BLOB_PATCH_JS      OK');

    // --- 4. clipboard patch ----------------------------------------------
    global.calls.length = 0;
    eval(read('CLIPBOARD_PATCH_JS.js'));
    await navigator.clipboard.writeText('hello');
    assert.ok(global.calls.includes('Android.copyToClipboard:hello'), 'copy must go through native');
    console.log('CLIPBOARD_PATCH_JS OK');

    console.log('\nAll injected scripts behave as expected.');
})().catch((error) => {
    console.error('FAILED:', error.message);
    process.exit(1);
});
