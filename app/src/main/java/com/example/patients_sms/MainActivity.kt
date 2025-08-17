package com.example.patients_sms

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater // NOUVEL IMPORT
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import com.example.patients_sms.databinding.ActivityMainBinding
import com.example.patients_sms.databinding.ListItemPatientBinding // NOUVEL IMPORT
import com.google.android.material.snackbar.Snackbar
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val allPatients = mutableListOf<Patient>()
    private val displayedPatients = mutableListOf<Patient>()
    private val selectedPatients = mutableSetOf<Patient>()
    private lateinit var adapter: PatientAdapter

    private val SMS_PERMISSION_CODE = 101

    private val messageTemplates = listOf(
        "Sélectionner un modèle...",
        "Cabinet fermé aujourd'hui",
        "Absence du [DATE] au [DATE]",
        "Absence du jour",
        "Reprise le [DATE]",
        "absence demain",
        "Rappel paiement",
        "Message personnalisé"
    )

    data class Patient(
        val nom: String,
        val nomNaissance: String,
        val prenom: String,
        val adresse: String,
        val codePostal: String,
        val ville: String,
        val tel1: String,
        val tel2: String,
        val email: String,
        var isSelected: Boolean = false
    ) {
        fun getDisplayName(): String = "$prenom $nom"
        fun getPrimaryPhone(): String = tel1.ifEmpty { tel2 }
        fun hasValidPhone(): Boolean = tel1.isNotEmpty() || tel2.isNotEmpty()
    }

    private val csvFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadCsvFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupAdapter()
        setupListeners()
        setupTemplateSpinner()
        checkPermissions()
        updateButtonStates()
        showWelcomeMessage()
    }

    private fun setupAdapter() {
        adapter = PatientAdapter()
        binding.patientListView.adapter = adapter
    }

    private fun setupListeners() {
        binding.loadCsvButton.setOnClickListener {
            csvFilePicker.launch("text/*")
        }

        binding.sendButton.setOnClickListener {
            if (checkSmsPermission()) {
                sendSmsToSelected()
            }
        }

        binding.selectAllButton.setOnClickListener {
            selectAll()
        }

        binding.deselectAllButton.setOnClickListener {
            deselectAll()
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterPatients(s.toString())
            }
        })

        binding.patientListView.setOnItemClickListener { _, _, position, _ ->
            val patient = displayedPatients[position]
            patient.isSelected = !patient.isSelected
            if (patient.isSelected) {
                selectedPatients.add(patient)
            } else {
                selectedPatients.remove(patient)
            }
            adapter.notifyDataSetChanged()
            updateButtonStates()
        }
    }

    private fun setupTemplateSpinner() {
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, messageTemplates)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.templateSpinner.adapter = spinnerAdapter

        binding.templateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val dateFormat = SimpleDateFormat("dd/MM", Locale.FRANCE)
                val today = dateFormat.format(Date())

                when (position) {
                    0 -> binding.messageEditText.setText("")
                    1 -> binding.messageEditText.setText("Cabinet kiné fermé exceptionnellement aujourd'hui. Merci de votre compréhension. Cordialement. L'équipe des kinés du pôle de santé ")
                    2 -> binding.messageEditText.setText("Bonjour je serai absent(e) du $today au [DATE]. Merci de votre compréhension. Cordialement. Votre Kiné")
                    3 -> binding.messageEditText.setText(" Bonjour je serai Absent(e) aujourd'hui. Merci de votre compréhension . Cordialement . Votre Kiné")
                    4 -> {
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                        val tomorrow = dateFormat.format(calendar.time)
                        binding.messageEditText.setText("Bonjour , Reprise des consultations le $tomorrow. Merci de votre patience. Votre kiné")
                    }
                    5 -> binding.messageEditText.setText("Bonjour , je serai absent(e) demain. Merci de votre compréhension. Cordialement. Votre kiné")
                    6 -> binding.messageEditText.setText(" Bonjour. merci de repasser au cabinet de kiné du pôle de santé la Francilienne avec votre carte vitale afin de solder les séances dues . Cordialement")
                    7 -> binding.messageEditText.setText("")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, notificationPermission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(notificationPermission), 102)
            }
        }
    }

    private fun checkSmsPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_CODE)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            SMS_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission SMS accordée", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Permission SMS refusée", Toast.LENGTH_SHORT).show()
                }
            }
            102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission de notification accordée", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Permission de notification refusée", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadCsvFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            allPatients.clear()
            selectedPatients.clear()
            var lineCount = 0
            reader.useLines { lines ->
                lines.forEach { line ->
                    lineCount++
                    if (lineCount > 1 && line.isNotBlank()) {
                        val parts = line.split(";").map { it.trim() }
                        if (parts.size >= 9) {
                            val patient = Patient(
                                nom = parts[0], nomNaissance = parts[1], prenom = parts[2],
                                adresse = parts[3], codePostal = parts[4], ville = parts[5],
                                tel1 = cleanPhoneNumber(parts[6]), tel2 = cleanPhoneNumber(parts[7]),
                                email = parts[8]
                            )
                            if (patient.hasValidPhone()) {
                                allPatients.add(patient)
                            }
                        }
                    }
                }
            }
            displayedPatients.clear()
            displayedPatients.addAll(allPatients)
            adapter.notifyDataSetChanged()
            updateButtonStates()
            val validCount = allPatients.size
            val message = "✅ $validCount patients chargés avec numéros valides"
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            AlertDialog.Builder(this)
                .setTitle("Erreur de chargement CSV")
                .setMessage("Impossible de lire le fichier CSV: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun cleanPhoneNumber(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
            .replace(Regex("^00"), "+")
            .let {
                if (it.startsWith("0") && it.length == 10) {
                    "+33" + it.substring(1)
                } else it
            }
    }

    private fun filterPatients(query: String) {
        displayedPatients.clear()
        if (query.isEmpty()) {
            displayedPatients.addAll(allPatients)
        } else {
            val lowerQuery = query.lowercase(Locale.getDefault())
            displayedPatients.addAll(allPatients.filter { patient ->
                patient.nom.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                        patient.prenom.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                        patient.ville.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                        patient.tel1.contains(query) ||
                        patient.tel2.contains(query)
            })
        }
        adapter.notifyDataSetChanged()
        updateButtonStates()
    }

    private fun selectAll() {
        displayedPatients.forEach { it.isSelected = true }
        selectedPatients.clear()
        selectedPatients.addAll(displayedPatients.filter { it.isSelected })
        adapter.notifyDataSetChanged()
        updateButtonStates()
    }

    private fun deselectAll() {
        displayedPatients.forEach { it.isSelected = false }
        selectedPatients.clear()
        adapter.notifyDataSetChanged()
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val selectedCount = selectedPatients.size
        val totalDisplayedCount = displayedPatients.size
        binding.patientCountText.text = "Patients: $totalDisplayedCount | Sélectionnés: $selectedCount"
        binding.sendButton.isEnabled = selectedCount > 0
        binding.sendButton.text = if (selectedCount > 0) {
            "📤 Envoyer SMS ($selectedCount)"
        } else {
            "📤 Envoyer SMS"
        }
        binding.selectAllButton.isEnabled = totalDisplayedCount > 0 && selectedCount < totalDisplayedCount
        binding.deselectAllButton.isEnabled = selectedCount > 0
    }

    private fun sendSmsToSelected() {
        val message = binding.messageEditText.text.toString().trim()
        if (message.isEmpty()) {
            Snackbar.make(binding.root, "⚠️ Message vide", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (message.contains("[DATE]") || message.contains("[HEURE]")) {
            Snackbar.make(binding.root, "⚠️ Remplacez les placeholders comme [DATE]", Snackbar.LENGTH_LONG).show()
            return
        }
        if (selectedPatients.isEmpty()) {
            Snackbar.make(binding.root, "👥 Aucun patient sélectionné", Snackbar.LENGTH_SHORT).show()
            return
        }
        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        var sentCount = 0
        var errorCount = 0
        selectedPatients.forEach { patient ->
            val phone = patient.getPrimaryPhone()
            if (phone.isNotEmpty()) {
                try {
                    val parts = smsManager.divideMessage(message)
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                    sentCount++
                } catch (e: Exception) {
                    errorCount++
                    e.printStackTrace()
                }
            } else {
                errorCount++
            }
        }
        var feedbackMessage = ""
        if (sentCount > 0) { feedbackMessage += "✅ $sentCount SMS envoyés. " }
        if (errorCount > 0) { feedbackMessage += "❌ $errorCount erreurs." }
        if (feedbackMessage.isEmpty()) { feedbackMessage = "Aucun SMS n'a pu être envoyé." }
        Snackbar.make(binding.root, feedbackMessage.trim(), Snackbar.LENGTH_LONG).show()
    }

    private fun showWelcomeMessage() {
        AlertDialog.Builder(this)
            .setTitle("👋 Bienvenue !")
            .setMessage("Chargez un fichier CSV pour commencer. \nAssurez-vous que le fichier est encodé en UTF-8.")
            .setPositiveButton("Compris", null)
            .show()
    }

    // --- DÉBUT DE LA CLASSE PatientAdapter MODIFIÉE ---
    private inner class PatientAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedPatients.size
        override fun getItem(position: Int): Any = displayedPatients[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val itemBinding: ListItemPatientBinding // Pour utiliser View Binding pour l'item

            if (convertView == null) {
                // Gonfler (inflate) le nouveau layout d'item en utilisant View Binding
                itemBinding = ListItemPatientBinding.inflate(LayoutInflater.from(this@MainActivity), parent, false)
            } else {
                // Réutiliser la vue existante et obtenir son binding
                itemBinding = ListItemPatientBinding.bind(convertView)
            }

            val patient = getItem(position) as Patient

            // Définir les données pour les vues dans l'item
            itemBinding.textViewPatientName.text = patient.getDisplayName()
            itemBinding.checkBoxPatientSelected.isChecked = patient.isSelected

            // Retourner la vue racine du layout de l'item (obtenue via itemBinding.root)
            return itemBinding.root
        }
    }
    // --- FIN DE LA CLASSE PatientAdapter MODIFIÉE ---
}
