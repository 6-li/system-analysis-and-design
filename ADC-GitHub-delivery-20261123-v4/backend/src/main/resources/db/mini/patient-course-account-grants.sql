-- Execute only after controlled creation of adc_course_patient_account.
GRANT CONNECT ON DATABASE adc_course_validation TO adc_course_patient_account;
GRANT USAGE ON SCHEMA adc_mini TO adc_course_patient_account,adc_course_mini_read;
GRANT SELECT(account_id,username,password_digest,disabled),
      INSERT(account_id,username,password_digest) ON adc_mini.patient_account TO adc_course_patient_account;
GRANT SELECT(account_id,disabled) ON adc_mini.patient_account TO adc_course_mini_read,adc_course_patient_intake,adc_course_mini_intake,adc_course_booking_writer;
GRANT SELECT(account_id,patient_id) ON adc_mini.patient_account_binding TO adc_course_mini_read,adc_course_mini_intake,adc_course_booking_writer;
GRANT SELECT(patient_id,identity_information) ON adc.patient TO adc_course_mini_intake;
GRANT INSERT(request_version) ON adc_mini.preliminary_request TO adc_course_mini_intake;
GRANT SELECT(account_id,patient_id,verified_request_id),
      INSERT(account_id,patient_id,verified_staff,verification_basis,verified_request_id)
      ON adc_mini.patient_account_binding TO adc_course_patient_intake;
