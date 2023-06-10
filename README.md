# Google Maps Timesheet

This Kotlin program is designed to generate a timesheet report based on a Location History JSON data of Google Maps. It calculates
the number of days worked in the office and provides feedback in terms of pending or bonus days.

**This project is supposed to be a sample implementation, not something to be used in production.**

## Setup

1. Download and install Gradle [from its official website](https://gradle.org/install).
   You should be able to run the following commands in your machine:

   ```shell
   gradle --version
   ```

2. Download your Maps Location History [following this guide](https://locationhistoryformat.com/guides/downloading/#downloading-the-data).
You should have a directory structure [similar to this one](https://locationhistoryformat.com/guides/general-structure/#general-structure):

   ```none
   Takeout/
     ├─ archive_browser.html
     └─ Location History/
          ├─ Records.json
          ├─ Settings.json
          └─ Semantic Location History/
               │  ...
               ├─ 2020/
               │    ├─ 2020_JANUARY.json
               │    ├─ 2020_FEBRUARY.json
               │    │  ...
               │    └─ 2020_DECEMBER.json
               └─ 2021/
                    ├─ 2021_JANUARY.json
                    ├─ 2021_FEBRUARY.json
                    │  ...
                    └─ 2021_DECEMBER.json
   ```

## Usage

1. Clone the repository:

    ```shell
    git clone https://github.com/enzo-santos/gmaps-timesheet.git
    cd gmaps-timesheet
    ```

2. Rename this project's *sample.env* file to *.env* and edit its contents:

   - The `TIMESHEET_LOCATION_HISTORY_FILE` key must contain the absolute path in your machine to the *Takeout/Location 
     History/Semantic Location History* directory, downloaded in step 2 of Setup.
   - The `TIMESHEET_WORKLOAD` key must contain your daily working hours, as an integer.
   - The `TIMESHEET_START_DATE` and `TIMESHEET_END_DATE` key must contain the range of days the algorithm should 
     consider to create your timesheet report. It usually goes from the day you began working in the company until now,
     but you can customize it according to your needs. The format of the dates must be `YYYY-MM-DD`.

3. Build and run the project:

    ```shell
    gradle run
    ```

The program will analyze the Google Maps Location History JSON data and provide the timesheet report. It will output
"X days pending" if the user has worked in the office less than the expected number of days, or "X days bonus"
otherwise.

### Implementation

The capabilities of this algorithm are described below as "*correctly*", while the limitations are described as 
"*incorrectly*".

Suppose Alice works from 8AM to 2PM (6 working hours).

- In a working day, Alice's location history confirms that they were physically present in the office.
  - If Alice worked from 8AM to 2PM, the algorithm **correctly** ignores this day (since 6 hours were worked).
  - If Alice worked from 8AM to 10AM, the algorithm **correctly** adds 4 hours as a debit to its timesheet.
  - If Alice worked from 8AM to 4PM, the algorithm **correctly** adds 2 hours as a credit to its timesheet.
  - If Alice worked from 10AM to 4PM, the algorithm **correctly** ignores this day (since 6 hours were worked, 
    regardless of when the work started).

- In a working day, Alice's location history indicates that they were not physically present in the office. In this 
  case, this algorithm will ignore this day.
  - If Alice was not working, the algorithm is **incorrect**, since it should have added 6 hours as a debit to 
    its timesheet.
  - If Alice was working remotely or had an external meeting outside the office, the algorithm is **incorrect**, since 
    it should have added some amount of time as a credit to its timesheet.
  - If Alice was sick or with a justified absence, the algorithm is **correct**.

- In a non-working day, Alice's location history shows that they were not present in the office. In this case, this 
  algorithm will ignore this day.
  - If Alice was not working, the algorithm is **correct**.
  - If Alice was working, the algorithm is **incorrect**, since it should have added some amount of time as a credit to 
    its timesheet.

- In a non-working day, Alice's location history reveals that they were physically present in the office. 
  In this case, this algorithm adds the amount of time Alice was in the office as a credit to its timesheet. 
  - If Alice was indeed working, the algorithm is **correct**.
  - If Alice was not engaged in their regular work activities and is instead participating in social events or 
    recreational activities within the office, the algorithm is **incorrect**, since it should have ignored this day.

Since this is a sample implementation, feel free to fork this repository and offer alternatives to handle these 
limitations.
