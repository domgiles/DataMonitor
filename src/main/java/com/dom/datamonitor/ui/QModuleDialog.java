package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.SqlValidator;
import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.GridOccupancy;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryRow;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal dialog for adding or editing a QModule.
 *
 * <p>The SQL must be validated (run once against the database) before OK is enabled.
 * Changing the SQL or the connection afterwards disables OK again until it is re-validated.
 * When editing, the module's existing SQL counts as validated until it is changed.
 *
 * <p>The dialog works on a copy: Cancel leaves the original {@link QModuleConfig} untouched.
 */
public class QModuleDialog extends JDialog {

    private static final Logger log = Logger.getLogger(QModuleDialog.class.getName());

    private static final Color OK_COLOUR    = new Color( 46, 160,  67);
    private static final Color WARN_COLOUR  = new Color(200, 130,   0);
    private static final Color ERROR_COLOUR = new Color(210,  50,  30);

    private final AppConfig.Settings settings;
    private final QModuleConfig      original;   // null when adding
    private       QModuleConfig      result;

    // ── Form fields ─────────────────────────────────────────────────────────
    private final JTextField                titleField   = new JTextField(22);
    private final JComboBox<ChartType>      chartType    = new JComboBox<>(ChartType.values());
    private final JComboBox<ProcessingMode> processing   = new JComboBox<>(ProcessingMode.values());
    private final JSpinner                  refreshRate  = new JSpinner(new SpinnerNumberModel(5, 1, 3600, 1));
    private final JCheckBox                 refreshDefault;
    private final JSpinner                  windowSize   = new JSpinner(new SpinnerNumberModel(120, 1, 86400, 10));
    private final JCheckBox                 windowDefault;
    private final JTextField                divideBy     = new JTextField(10);

    private final JCheckBox      ownConnection  = new JCheckBox("Use a different connection");
    private final JTextField     connUser       = new JTextField(14);
    private final JPasswordField connPassword   = new JPasswordField(14);
    private final JTextField     connString     = new JTextField(22);
    private final JTextField     connWallet     = new JTextField(22);
    private final JPanel         connPanel      = new JPanel(new GridBagLayout());

    private final GridPicker     gridPicker;
    private final GridLocation   originalLocation;   // explicit form, for "unchanged" detection

    private final RSyntaxTextArea sqlEditor = new RSyntaxTextArea(10, 70);

    // ── Validation ──────────────────────────────────────────────────────────
    private final JButton           validateButton = new JButton("Validate SQL");
    private final JLabel            statusLabel    = new JLabel(" ");
    private final DefaultTableModel previewModel   = new DefaultTableModel(new Object[]{"Label", "Value", "Colour"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JButton okButton = new JButton();

    /** SQL + connection that last validated successfully, or {@code null}. */
    private String                validatedFingerprint;
    /** Fingerprint of the last failed validation, so its error stays visible until something changes. */
    private String                failedFingerprint;
    private SqlValidator.Result   lastValidation;
    private SwingWorker<?, ?>     runningValidation;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows the dialog and waits for the user.
     *
     * @param module    module to edit, or {@code null} to add a new one
     * @param occupancy grid cells taken by the other modules
     * @return the new or edited module, or empty if cancelled
     */
    public static Optional<QModuleConfig> show(Window owner, Theme theme, AppConfig.Settings settings,
                                               QModuleConfig module, GridOccupancy occupancy) {
        QModuleDialog dialog = new QModuleDialog(owner, theme, settings, module, occupancy);
        dialog.setVisible(true);   // modal: blocks until closed
        return Optional.ofNullable(dialog.result);
    }

    private QModuleDialog(Window owner, Theme theme, AppConfig.Settings settings,
                          QModuleConfig module, GridOccupancy occupancy) {
        super(owner, module == null ? "Add QModule" : "Edit QModule: " + module.getTitle(),
              ModalityType.APPLICATION_MODAL);
        this.settings = settings;
        this.original = module;

        refreshDefault = new JCheckBox("Default (" + settings.getRefreshRate() + "s)");
        windowDefault  = new JCheckBox("Default (" + settings.getWindowSize()  + "s)");

        GridLocation initial = null;
        if (module != null) {
            try {
                initial = GridLocation.parse(module.getGrid());
            } catch (ConfigException e) {
                log.warning("Unparseable grid on module being edited: " + module.getGrid());
            }
        }
        gridPicker       = new GridPicker(occupancy, initial != null ? initial : GridPicker.firstFreeCell(occupancy));
        originalLocation = (initial != null) ? gridPicker.explicit(initial) : null;

        configureSqlEditor(theme);
        populate(module);
        setContentPane(buildContent());
        wireListeners();
        refreshState();

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private JComponent buildContent() {
        // Top: settings form (left) + location picker (right)
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Module"));
        int row = 0;
        addRow(form, row++, "Title",       titleField);
        addRow(form, row++, "Chart type",  chartType);
        addRow(form, row++, "Processing",  processing);
        addRow(form, row++, "Refresh (s)", inline(refreshRate, refreshDefault));
        addRow(form, row++, "Window (s)",  inline(windowSize, windowDefault));
        addRow(form, row++, "Divide by",   divideBy);
        GridBagConstraints gc = cell(0, row++);
        gc.gridwidth = 2;
        form.add(ownConnection, gc);
        gc = cell(0, row);
        gc.gridwidth = 2;
        gc.fill      = GridBagConstraints.HORIZONTAL;
        form.add(connPanel, gc);

        int r = 0;
        addRow(connPanel, r++, "Username",       connUser);
        addRow(connPanel, r++, "Password",       connPassword);
        addRow(connPanel, r++, "Connect string", connString);
        addRow(connPanel, r,   "Wallet (ADB)",   connWallet);
        connPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));

        JPanel location = new JPanel(new BorderLayout(0, 6));
        location.setBorder(BorderFactory.createTitledBorder("Location"));
        JScrollPane pickerScroll = new JScrollPane(gridPicker);
        pickerScroll.setBorder(BorderFactory.createEmptyBorder());
        pickerScroll.setPreferredSize(new Dimension(320, Math.min(260, gridPicker.getPreferredSize().height + 4)));
        location.add(pickerScroll, BorderLayout.CENTER);
        location.add(legend(), BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.add(form,     BorderLayout.WEST);
        top.add(location, BorderLayout.CENTER);

        // Middle: SQL editor + validation
        RTextScrollPane sqlScroll = new RTextScrollPane(sqlEditor);
        sqlScroll.setLineNumbersEnabled(true);

        JTable preview = new JTable(previewModel);
        preview.setFillsViewportHeight(true);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setPreferredSize(new Dimension(0, 110));

        JPanel validateRow = new JPanel(new BorderLayout(8, 0));
        validateRow.add(validateButton, BorderLayout.WEST);
        validateRow.add(statusLabel,    BorderLayout.CENTER);

        JPanel sql = new JPanel(new BorderLayout(0, 6));
        sql.setBorder(BorderFactory.createTitledBorder("SQL"));
        sql.add(sqlScroll,   BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(validateRow,   BorderLayout.NORTH);
        south.add(previewScroll, BorderLayout.CENTER);
        sql.add(south, BorderLayout.SOUTH);

        // Bottom: buttons
        okButton.setText(original == null ? "Add" : "Save");
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        okButton.addActionListener(e -> accept());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(okButton);
        getRootPane().setDefaultButton(okButton);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(top,     BorderLayout.NORTH);
        root.add(sql,     BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        // Esc cancels
        root.registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        return root;
    }

    private static JComponent legend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(swatch(new Color(128, 128, 128), false)); p.add(new JLabel("taken"));
        p.add(swatch(new Color(46, 160, 67), false));   p.add(new JLabel("free"));
        p.add(swatch(new Color(46, 160, 67), true));    p.add(new JLabel("selected (drag to span)"));
        return p;
    }

    private static JComponent swatch(Color colour, boolean solid) {
        return new JComponent() {
            { setPreferredSize(new Dimension(14, 12)); }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(colour);
                if (solid || colour.equals(new Color(128, 128, 128))) g.fillRoundRect(0, 0, 13, 11, 4, 4);
                else g.drawRoundRect(0, 0, 13, 11, 4, 4);
            }
        };
    }

    private static JPanel inline(JComponent a, JComponent b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(a);
        p.add(b);
        return p;
    }

    private static void addRow(JPanel panel, int row, String label, JComponent field) {
        panel.add(new JLabel(label), cell(0, row));
        GridBagConstraints gc = cell(1, row);
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        panel.add(field, gc);
    }

    private static GridBagConstraints cell(int x, int y) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx  = x;
        gc.gridy  = y;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(2, 4, 2, 4);
        return gc;
    }

    private void configureSqlEditor(Theme theme) {
        sqlEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        sqlEditor.setCodeFoldingEnabled(false);
        sqlEditor.setTabsEmulated(true);   // tabs would end up in the YAML
        sqlEditor.setTabSize(4);
        sqlEditor.setHighlightCurrentLine(true);

        String themeFile = (theme == Theme.DARK) ? "dark.xml" : "default.xml";
        try (InputStream in = getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + themeFile)) {
            if (in != null) org.fife.ui.rsyntaxtextarea.Theme.load(in).apply(sqlEditor);
        } catch (IOException e) {
            log.log(Level.FINE, "Could not load editor theme " + themeFile, e);
        }
        sqlEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Populate / read back
    // ─────────────────────────────────────────────────────────────────────────

    private void populate(QModuleConfig m) {
        // Greyed-out spinners show the dashboard default while "Default" is ticked
        refreshRate.setValue(settings.getRefreshRate());
        windowSize.setValue(settings.getWindowSize());

        if (m == null) {
            titleField.setText("New QModule");
            chartType.setSelectedItem(ChartType.LINE);
            processing.setSelectedItem(ProcessingMode.NONE);
            refreshDefault.setSelected(true);
            windowDefault.setSelected(true);
            divideBy.setText("1");
            sqlEditor.setText("SELECT 'Active Sessions', count(*)\nFROM v$session\nWHERE status = 'ACTIVE' AND type = 'USER'");
            return;
        }

        titleField.setText(m.getTitle());
        chartType.setSelectedItem(parseOr(() -> ChartType.fromString(m.getChartType()), ChartType.LINE));
        processing.setSelectedItem(parseOr(() -> ProcessingMode.fromString(m.getProcessingMode()), ProcessingMode.NONE));

        refreshDefault.setSelected(m.getRefreshRate() == null);
        if (m.getRefreshRate() != null) refreshRate.setValue(m.getRefreshRate());
        windowDefault.setSelected(m.getWindowSize() == null);
        if (m.getWindowSize() != null) windowSize.setValue(m.getWindowSize());
        divideBy.setText(formatNumber(m.getDivideBy()));

        ConnectionConfig c = m.getConnection();
        ownConnection.setSelected(c != null);
        if (c != null) {
            connUser.setText(c.getUsername());
            connPassword.setText(c.getPassword());
            connString.setText(c.getConnectString());
            connWallet.setText(c.getCredentialsFile());
        }

        sqlEditor.setText(m.getSql() == null ? "" : m.getSql().strip());
        sqlEditor.setCaretPosition(0);
        // The saved SQL is accepted as-is until it is changed
        validatedFingerprint = fingerprint();
    }

    private interface Parser<T> { T parse() throws ConfigException; }

    private static <T> T parseOr(Parser<T> parser, T fallback) {
        try {
            return parser.parse();
        } catch (ConfigException e) {
            return fallback;
        }
    }

    private static String formatNumber(double d) {
        return (d == Math.rint(d)) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Divide-by value, or {@code null} if it is not a positive number. */
    private Double parsedDivideBy() {
        try {
            double d = Double.parseDouble(divideBy.getText().strip().replace(",", ""));
            return (d > 0 && Double.isFinite(d)) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ConnectionConfig connectionFromFields() {
        if (!ownConnection.isSelected()) return null;
        ConnectionConfig c = new ConnectionConfig();
        c.setUsername(connUser.getText().strip());
        c.setPassword(new String(connPassword.getPassword()));
        c.setConnectString(connString.getText().strip());
        String wallet = connWallet.getText().strip();
        c.setCredentialsFile(wallet.isEmpty() ? null : wallet);
        return c;
    }

    private ConnectionConfig effectiveConnection() {
        ConnectionConfig own = connectionFromFields();
        return (own != null) ? own : settings.getEffectiveConnection();
    }

    /** What validation depends on: changing any of it requires validating again. */
    private String fingerprint() {
        ConnectionConfig c = effectiveConnection();
        return sqlEditor.getText().strip() + "\u0000"
            + (c == null ? "" : c.getUsername() + "\u0000" + c.getPassword() + "\u0000"
                                + c.getConnectString() + "\u0000" + c.getCredentialsFile());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Behaviour
    // ─────────────────────────────────────────────────────────────────────────

    private void wireListeners() {
        DocumentListener changed = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { refreshState(); }
            @Override public void removeUpdate(DocumentEvent e)  { refreshState(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshState(); }
        };
        for (JTextComponent t : List.of(titleField, divideBy, sqlEditor, connUser, connPassword, connString, connWallet)) {
            t.getDocument().addDocumentListener(changed);
        }
        chartType.addActionListener(e -> refreshState());
        refreshDefault.addActionListener(e -> refreshState());
        windowDefault.addActionListener(e -> refreshState());
        ownConnection.addActionListener(e -> refreshState());
        gridPicker.setOnChange(loc -> refreshState());

        validateButton.addActionListener(e -> runValidation());
        // Cmd/Ctrl+Enter validates from inside the editor
        sqlEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "dm-validate");
        sqlEditor.getActionMap().put("dm-validate", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { runValidation(); }
        });
        validateButton.setToolTipText("Run the query once and preview the result (Cmd/Ctrl+Enter)");
    }

    /** Enables/disables controls and updates the status line from the current field values. */
    private void refreshState() {
        boolean timeSeries = ((ChartType) chartType.getSelectedItem()).isTimeSeries();
        refreshRate.setEnabled(!refreshDefault.isSelected());
        windowDefault.setEnabled(timeSeries);
        windowSize.setEnabled(timeSeries && !windowDefault.isSelected());
        for (Component c : connPanel.getComponents()) c.setEnabled(ownConnection.isSelected());

        divideBy.setForeground(parsedDivideBy() == null ? ERROR_COLOUR : UIManager.getColor("TextField.foreground"));

        boolean validated = Objects.equals(validatedFingerprint, fingerprint());
        boolean validating = runningValidation != null;
        validateButton.setEnabled(!validating);

        String problem = formProblem();
        if (validating) {
            setStatus("Validating…", null);
        } else if (validated && lastValidation != null) {
            List<String> warnings = SqlValidator.warningsFor(
                (ChartType) chartType.getSelectedItem(), lastValidation.preview());
            if (warnings.isEmpty()) setStatus(lastValidation.message(), OK_COLOUR);
            else                    setStatus(lastValidation.message() + ". " + String.join(" ", warnings), WARN_COLOUR);
        } else if (validated) {
            setStatus("SQL unchanged since it was saved.", OK_COLOUR);
        } else if (lastValidation != null && !lastValidation.ok()
                   && Objects.equals(failedFingerprint, fingerprint())) {
            setStatus(lastValidation.message(), ERROR_COLOUR);
        } else {
            setStatus("Validate the SQL before " + (original == null ? "adding" : "saving") + " this module.", null);
        }

        okButton.setEnabled(validated && !validating && problem == null);
        okButton.setToolTipText(problem != null ? problem : validated ? null : "Validate the SQL first");
    }

    /** First problem with the non-SQL fields, or {@code null}. */
    private String formProblem() {
        if (titleField.getText().isBlank())   return "Enter a title.";
        if (parsedDivideBy() == null)         return "Divide by must be a positive number.";
        if (gridPicker.getSelection() == null) return "Pick a location.";
        ConnectionConfig own = connectionFromFields();
        if (own != null && (own.getUsername().isEmpty() || own.getPassword().isEmpty()
                            || own.getConnectString().isEmpty())) {
            return "Fill in username, password and connect string for the connection.";
        }
        return null;
    }

    private void setStatus(String text, Color colour) {
        statusLabel.setText(text);
        statusLabel.setToolTipText(text);
        statusLabel.setForeground(colour != null ? colour : UIManager.getColor("Label.foreground"));
    }

    private void runValidation() {
        if (runningValidation != null) return;
        if (effectiveConnection() == null) {
            setStatus("No connection configured.", ERROR_COLOUR);
            return;
        }

        String           sql         = sqlEditor.getText().strip();
        ConnectionConfig conn        = effectiveConnection();
        ChartType        type        = (ChartType) chartType.getSelectedItem();
        Double           divisor     = parsedDivideBy();
        String           fingerprint = fingerprint();

        previewModel.setRowCount(0);
        runningValidation = new SwingWorker<SqlValidator.Result, Void>() {
            @Override protected SqlValidator.Result doInBackground() {
                return SqlValidator.validate(sql, conn, type, divisor != null ? divisor : 1.0);
            }

            @Override protected void done() {
                runningValidation = null;
                try {
                    lastValidation = get();
                } catch (Exception e) {
                    lastValidation = new SqlValidator.Result(false, "Validation failed: " + e.getMessage(),
                        List.of(), List.of());
                }
                if (lastValidation.ok()) {
                    validatedFingerprint = fingerprint;
                    failedFingerprint    = null;
                    for (QueryRow r : lastValidation.preview()) {
                        previewModel.addRow(new Object[]{r.key(), r.value(), r.color()});
                    }
                } else {
                    validatedFingerprint = null;
                    failedFingerprint    = fingerprint;
                }
                refreshState();
            }
        };
        refreshState();
        runningValidation.execute();
    }

    private void accept() {
        if (!okButton.isEnabled()) return;

        QModuleConfig m = (original != null) ? original.copy() : new QModuleConfig();
        m.setTitle(titleField.getText().strip());
        m.setChartType(((ChartType) chartType.getSelectedItem()).name());

        ProcessingMode mode = (ProcessingMode) processing.getSelectedItem();
        // Keep the YAML tidy: NONE is the default, so leave it unset unless it was set before
        boolean hadMode = original != null && original.getProcessingMode() != null;
        m.setProcessingMode(mode == ProcessingMode.NONE && !hadMode ? null : mode.name());

        m.setRefreshRate(refreshDefault.isSelected() ? null : (Integer) refreshRate.getValue());
        boolean timeSeries = ((ChartType) chartType.getSelectedItem()).isTimeSeries();
        m.setWindowSize(!timeSeries || windowDefault.isSelected() ? null : (Integer) windowSize.getValue());
        m.setDivideBy(parsedDivideBy());
        m.setConnection(connectionFromFields());
        m.setSql(sqlEditor.getText().strip());

        // Keep the original grid text (e.g. "R2,C1*") if the location was not changed
        GridLocation chosen = gridPicker.getSelection();
        m.setGrid(chosen.equals(originalLocation) ? original.getGrid() : chosen.toSpec());

        result = m;
        dispose();
    }

    @Override
    public void dispose() {
        if (runningValidation != null) runningValidation.cancel(true);
        super.dispose();
    }
}
