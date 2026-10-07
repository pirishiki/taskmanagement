# 設計図の中で使う「変数」（あとから値を変えられる名前）

# 作るものの名前の頭に付ける文字（例：taskmanagement-vpc）
variable "project_name" {
  type    = string
  default = "taskmanagement"
}
