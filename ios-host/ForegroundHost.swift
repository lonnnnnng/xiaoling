import UIKit
import XiaoLingShared

// long: 这是单窗口Simulator验收宿主，配置只从启动进程内存读取，不提供用户凭据持久化或后台运行。
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var controller: ForegroundController?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let view = ForegroundController()
        controller = view
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = view
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
    func applicationDidBecomeActive(_ application: UIApplication) { controller?.lifecycle("ACTIVE", application.applicationState.rawValue) }
    func applicationWillResignActive(_ application: UIApplication) { controller?.lifecycle("RESIGN_ACTIVE", application.applicationState.rawValue) }
    func applicationDidEnterBackground(_ application: UIApplication) { controller?.lifecycle("BACKGROUND", application.applicationState.rawValue) }
    func applicationWillTerminate(_ application: UIApplication) { controller?.close() }
}

final class ForegroundController: UIViewController {
    private let goal = UITextView()
    private let status = UILabel()
    private let output = UITextView()
    private let runButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private var host: IosForegroundAgentHost?
    private var eventFile: URL?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.text = "小灵 · 前台验收"
        title.font = .systemFont(ofSize: 26, weight: .bold)
        goal.font = .systemFont(ofSize: 17)
        goal.layer.borderWidth = 1
        goal.layer.borderColor = UIColor.separator.cgColor
        goal.layer.cornerRadius = 8
        goal.accessibilityIdentifier = "foreground.goal"
        goal.heightAnchor.constraint(equalToConstant: 125).isActive = true
        status.font = .systemFont(ofSize: 16, weight: .medium)
        status.numberOfLines = 0
        status.accessibilityIdentifier = "foreground.status"
        output.font = .systemFont(ofSize: 16)
        output.isEditable = false
        output.accessibilityIdentifier = "foreground.result"
        runButton.setTitle("运行", for: .normal)
        runButton.accessibilityIdentifier = "foreground.run"
        runButton.addTarget(self, action: #selector(run), for: .touchUpInside)
        cancelButton.setTitle("取消", for: .normal)
        cancelButton.accessibilityIdentifier = "foreground.cancel"
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [runButton, cancelButton])
        buttons.distribution = .fillEqually
        buttons.heightAnchor.constraint(equalToConstant: 48).isActive = true
        let stack = UIStackView(arrangedSubviews: [title, goal, buttons, status, output])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16)
        ])
        configure()
    }

    private func configure() {
        let env = ProcessInfo.processInfo.environment
        goal.text = env["XIAOLING_GOAL"] ?? "列出工作区，读取验收.txt，再读取 example 页面，最后总结实际内容。"
        do {
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            eventFile = docs.appendingPathComponent("foreground-events.jsonl")
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("xiaoling-foreground-" + UUID().uuidString)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
            let marker = env["XIAOLING_MARKER"] ?? UUID().uuidString
            try ("公开验收文件。标记：" + marker + "。这段正文只用于检查模型确实读取文件。")
                .write(to: root.appendingPathComponent("验收.txt"), atomically: true, encoding: .utf8)
            guard let endpoint = env["XIAOLING_MODEL_ENDPOINT"], let model = env["XIAOLING_MODEL"], let key = env["XIAOLING_MODEL_KEY"], !key.isEmpty else {
                status.text = "未注入验收模型配置"
                runButton.isEnabled = false
                return
            }
            host = IosForegroundAgentHost(workspaceRoot: root.path, pages: ["example": "https://example.com/"], endpoint: endpoint, model: model, apiKey: key, onEvent: { [weak self] raw in self?.render(raw) })
            status.text = "模型已配置 · 等待运行"
            cancelButton.isEnabled = false
        } catch {
            status.text = "验收工作区创建失败"
            runButton.isEnabled = false
        }
    }
    @objc private func run() {
        view.endEditing(true)
        record(["action": "TAP_RUN", "application_state": UIApplication.shared.applicationState.rawValue])
        if host?.start(goal: goal.text) != true { status.text = "当前前台会话不能开始运行" }
    }
    @objc private func cancel() {
        record(["action": "TAP_CANCEL", "application_state": UIApplication.shared.applicationState.rawValue])
        host?.cancel()
    }
    private func render(_ raw: String) {
        guard let data = raw.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        record(object)
        let state = object["status"] as? String ?? "FAILED"
        let tools = object["tools"] as? [String] ?? []
        status.text = ["RUNNING": "运行中", "COMPLETED": "已完成", "CANCELLED": "已取消", "FAILED": "执行失败"][state]! + " · \(tools.count) 个工具"
        runButton.isEnabled = state != "RUNNING"
        cancelButton.isEnabled = state == "RUNNING"
        output.text = (object["summary"] as? String ?? "") + "\n\n" + tools.joined(separator: "\n")
    }
    func lifecycle(_ state: String, _ applicationState: Int) { record(["lifecycle": state, "application_state": applicationState]) }
    func close() { host?.close() }
    private func record(_ object: [String: Any]) {
        // long: 仅本验收宿主保存公开夹具结果和生命周期，不保存配置、请求头、模型原响应或正式用户文件。
        guard let path = eventFile, let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return }
        if !FileManager.default.fileExists(atPath: path.path) { FileManager.default.createFile(atPath: path.path, contents: nil) }
        guard let handle = try? FileHandle(forWritingTo: path) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: data + Data([10]))
    }
}
