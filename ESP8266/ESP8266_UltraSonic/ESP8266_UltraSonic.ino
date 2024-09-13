#include <FirebaseESP8266.h>
#include <ESP8266WiFi.h>
#include <WiFiUdp.h>

// Replace with your network credentials
#define WIFI_SSID "WIFI_SSID_HERE"
#define WIFI_PASSWORD "PASSWORD_HERE"

// NTP Server details
const char* ntpServer = "pool.ntp.org";
const int timeZoneOffset = 5 * 3600 + 1800;  // IST = UTC + 5:30

// UDP instance for NTP communication
WiFiUDP udp;
const int NTP_PACKET_SIZE = 48;
byte ntpPacketBuffer[NTP_PACKET_SIZE];

// Function to get NTP time
unsigned long getTime() {
  udp.begin(2390);  // Start UDP communication on port 2390
  sendNTPPacket(ntpServer);  // Send an NTP request packet
  delay(1000);  // Wait for a response

  // Check if a response is received
  if (udp.parsePacket()) {
    // Read the received NTP response into the buffer
    udp.read(ntpPacketBuffer, NTP_PACKET_SIZE);

    // Extract the time from the response
    unsigned long highWord = word(ntpPacketBuffer[40], ntpPacketBuffer[41]);
    unsigned long lowWord = word(ntpPacketBuffer[42], ntpPacketBuffer[43]);
    unsigned long secsSince1900 = (highWord << 16) | lowWord;

    // Convert NTP time to UNIX epoch time
    const unsigned long seventyYears = 2208988800UL;
    unsigned long epoch = secsSince1900 - seventyYears;

    // Adjust for the local timezone (IST)
    return epoch;
  }
  return 0;  // Return 0 if no response is received
}

// Function to send NTP request packet
void sendNTPPacket(const char* address) {
  memset(ntpPacketBuffer, 0, NTP_PACKET_SIZE);  // Clear the buffer
  ntpPacketBuffer[0] = 0b11100011;  // LI, Version, Mode settings

  // Send the NTP request to the server at port 123
  udp.beginPacket(address, 123);
  udp.write(ntpPacketBuffer, NTP_PACKET_SIZE);
  udp.endPacket();
}

// Function to get the formatted date from UNIX time
String getFormattedDate(unsigned long unixTime) {
  unixTime += timeZoneOffset;  // Adjust for IST if needed

  // Calculate components of the date
  int year = 1970;
  unsigned long secondsInYear;
  while ((secondsInYear = (isLeapYear(year) ? 31622400UL : 31536000UL)) <= unixTime) {
    unixTime -= secondsInYear;
    year++;
  }

  // Calculate day and month
  int month = 0;
  const int monthDays[] = {31, 28 + isLeapYear(year), 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  while (unixTime >= monthDays[month] * 86400UL) {
    unixTime -= monthDays[month] * 86400UL;
    month++;
  }

  // Adjust the day calculation by subtracting 1 day
  int day = (unixTime / 86400UL);  // Day of the month

  // Format date as yyyy-mm-dd
  char date[11];
  sprintf(date, "%04d-%02d-%02d", year, month + 1, day + 1);  // Add 1 to day to match the current date
  return String(date);
}

// Helper function to check if a year is a leap year
bool isLeapYear(int year) {
  return ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0));
}

// Replace with your Firebase project details
#define FIREBASE_HOST "<project_id>.firebaseio.com"  // Firebase host (without "https://")
#define FIREBASE_AUTH "<AUTH_TOKEN_HERE>"

// Sensor pin definitions
#define TRIG_PIN 5  // D1
#define ECHO_PIN 4  // D2

// Firebase objects for data handling
FirebaseData firebaseData;
FirebaseConfig firebaseConfig;
FirebaseAuth firebaseAuth;
FirebaseJson json;

// Setup function to initialize Wi-Fi and Firebase
void setup() {
  Serial.begin(115200);

  // Connect to Wi-Fi
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    Serial.print(".");
    delay(300);
  }
  Serial.println();
  Serial.print("Connected with IP: ");
  Serial.println(WiFi.localIP());

  // Configure Firebase
  firebaseConfig.host = FIREBASE_HOST;
  firebaseConfig.signer.tokens.legacy_token = FIREBASE_AUTH;  // Use legacy token for authentication
  Firebase.begin(&firebaseConfig, &firebaseAuth);
  Firebase.reconnectWiFi(true);

  // Set sensor pins
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
}

// Loop function to read sensor data and update Firebase
void loop() {
  // Get the distance reading from the sensor
  float distance = getDistance() + 2.0;  // Add 2cm for accuracy

  // Get current time
  unsigned long unixTime = getTime();
  String dateStr = getFormattedDate(unixTime);

  // Construct the Firebase path using date and time
  String path = "/sensorData/" + dateStr + "/" + String(unixTime);

  // Update Firebase with the sensor data
  if (Firebase.setFloat(firebaseData, path, distance)) {
    Serial.println("Data updated to Firebase successfully.");
  } else {
    Serial.println("Failed to update data to Firebase.");
    Serial.println("REASON: " + firebaseData.errorReason());
  }

  delay(5000);  // Adjust the delay as needed
}

// Function to get the distance from the ultrasonic sensor
float getDistance() {
  // Trigger the sensor with a short pulse
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  // Read the echo time
  long duration = pulseIn(ECHO_PIN, HIGH);

  // Calculate the distance in cm
  float distanceCm = duration * 0.034 / 2;
  return distanceCm;
}

// Function to get formatted date/time string
String getDateTimeString(const char* format) {
  time_t now;
  struct tm timeinfo;
  char timeStringBuff[50];

  // Get the current time and format it
  time(&now);
  localtime_r(&now, &timeinfo);
  strftime(timeStringBuff, sizeof(timeStringBuff), format, &timeinfo);

  return String(timeStringBuff);
}
