// 仅在 DRPY2 的 RSA 自检代码里被引用（本目录的 4 条规则不走 RSA）。
// 给空实现，避免该自检一旦执行就抛错。
globalThis.NODERSA = {
    encryptRSAWithPublicKey: function (d) { return ''; },
    encryptRSAWithPrivateKey: function (d) { return ''; },
    decryptRSAWithPrivateKey: function (d) { return ''; },
    decryptRSAWithPublicKey: function (d) { return ''; },
    sign: function () { return ''; },
    verify: function () { return false; },
    NodeRSA: function () {
        this.setOptions = function () { };
        this.importKey = function () { };
        this.encrypt = function () { return ''; };
        this.decrypt = function () { return ''; };
    }
};
