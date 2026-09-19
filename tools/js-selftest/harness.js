// Minimal DOM/WebView stub: just enough surface for the GeminiAssist injections.
// It deliberately records every call so run.js can assert on the behaviour.

global.calls = [];

function makeEl(tag, editable) {
    return {
        tagName: tag,
        isContentEditable: !!editable,
        offsetParent: {},
        disabled: false,
        readOnly: false,
        value: '',
        _text: '',
        get textContent() {
            return this._text;
        },
        set textContent(v) {
            this._text = v;
        },
        getBoundingClientRect: () => ({ width: 300, height: 40 }),
        focus: function () {
            global.calls.push('focus:' + tag);
        },
        click: function () {
            global.calls.push('click:' + tag);
        },
        dispatchEvent: function (event) {
            global.calls.push('event:' + event.type);
            return true;
        },
        scrollIntoView: function () {},
    };
}

const contentEditable = makeEl('DIV', true);

global.document = {
    // The injections must call querySelectorAll with ONE selector string; passing the
    // selector list as separate arguments is invalid JS and is caught here.
    querySelectorAll: function (selector) {
        global.calls.push('querySelectorAll');
        if (arguments.length !== 1) {
            throw new Error('querySelectorAll got ' + arguments.length + ' arguments');
        }
        if (typeof selector !== 'string' || selector.indexOf('undefined') !== -1) {
            throw new Error('invalid selector argument: ' + selector);
        }
        return [contentEditable];
    },
    execCommand: function () {
        return false; // force the textContent fallback path
    },
    createRange: function () {
        return { selectNodeContents: function () {} };
    },
    body: {},
    documentElement: {},
};

global.window = global;
global.navigator.clipboard = global.navigator.clipboard || { writeText: function () {} };
global.getSelection = function () {
    return { removeAllRanges() {}, addRange() {} };
};
global.Event = function (type, opts) {
    this.type = type;
    this.bubbles = !!(opts && opts.bubbles);
};
global.MutationObserver = function () {
    this.observe = function () {};
    this.disconnect = function () {};
};

global.Android = {
    showSoftKeyboard: function () {
        global.calls.push('Android.showSoftKeyboard');
    },
    copyToClipboard: function (text) {
        global.calls.push('Android.copyToClipboard:' + text);
    },
    processBlob: function () {
        global.calls.push('Android.processBlob');
    },
};

module.exports = { contentEditable: contentEditable };
