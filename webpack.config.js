module.exports = {
    externals: {
        react: "React",
    },
    externalsType: "window",
    entry: './src/agent-bridge.js',
    output: {
        filename: 'extension.js',
        path: __dirname,
        library: {
            type: "module",
        }
    },
    experiments: {
        outputModule: true,
    },
    mode: "production",
    module: {
        rules: [
          {
            test: /\.css$/,
            type: "asset/source",
          },
        ],
      },
};
